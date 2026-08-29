package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.HostConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the reconciler may and may not destroy.
 *
 * <p>The dangerous direction is not "an orphan survived" — it is "a live sandbox was destroyed". The previous
 * implementation scoped removal by launcher generation and its documentation claimed that made concurrent
 * launchers safe. It did the opposite: it force-removed <em>running</em> containers belonging to every other
 * generation, and was observed deleting another process's sandboxes mid-run, which is what turned this suite
 * red under concurrency. Its safety test could not have caught that, because the container it asserted
 * survived carried the reconciler's own generation.
 *
 * <p>Removal is now judged by age, and these tests pin both halves: young containers are never touched no
 * matter who owns them, and abandoned ones are reclaimed no matter who owns them — including this generation,
 * the case generation-scoping could never reach.
 */
class OrphanReconciliationTests {
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Test
    @Timeout(120)
    void aRunningSandboxFromAnotherLiveLauncherIsNeverReclaimed() {
        // The property the previous implementation promised and broke. A launcher's liveness is not visible to
        // another process, so anything inside the deadline window has to be assumed live.
        String foreignButLive = createContainer(SandboxLabels.of("another-live-launcher", UUID.randomUUID(), "v1"));
        SandboxTestSupport.docker().startContainerCmd(foreignButLive).exec();

        int removed = new OrphanSandboxReconciler(
                SandboxTestSupport.docker(), "my-generation", DEADLINE).reconcile();

        assertThat(removed).isZero();
        assertThat(containerExists(foreignButLive)).isTrue();
        remove(foreignButLive);
    }

    @Test
    @Timeout(120)
    void aSandboxPastItsDeadlineIsReclaimedWhicheverLauncherLeftIt() {
        String foreign = createContainer(SandboxLabels.of("generation-that-crashed", UUID.randomUUID(), "v1"));
        // Orphaned by *this* generation. Nothing reclaimed these before, because the reconciler skipped its own
        // generation entirely — so a launcher that crashed and restarted under the same generation leaked.
        String own = createContainer(SandboxLabels.of("my-generation", UUID.randomUUID(), "v1"));
        // A container that is not ours, wearing a name that looks like it might be. Matching on anything other
        // than the managed label — a name prefix, an image, "everything stopped" — is how a reconciler
        // eventually deletes somebody's database.
        String bystander = createContainer(Map.of("com.example.kaas-lookalike", "true"));

        // Time moves rather than the test sleeping for the real window, so the age rule is exercised exactly
        // as written instead of being approximated by a shorter one only the test ever uses.
        Clock afterAbandonment = Clock.fixed(
                Instant.now().plus(Duration.ofMinutes(30)), ZoneOffset.UTC);
        int removed = new OrphanSandboxReconciler(
                SandboxTestSupport.docker(), "my-generation", DEADLINE, afterAbandonment).reconcile();

        assertThat(removed).isGreaterThanOrEqualTo(2);
        assertThat(containerExists(foreign)).isFalse();
        assertThat(containerExists(own)).isFalse();
        assertThat(containerExists(bystander)).isTrue();
        remove(bystander);
    }

    @Test
    @Timeout(120)
    void reconciliationConsidersOnlyContainersCarryingTheManagedLabel() {
        String bystander = createContainer(Map.of("unrelated", "true"));
        String managedOne = createContainer(SandboxLabels.of("some-generation", UUID.randomUUID(), "v1"));

        List<com.github.dockerjava.api.model.Container> managed = new OrphanSandboxReconciler(
                        SandboxTestSupport.docker(), "any", DEADLINE).managedContainers();

        // Asserting only that the bystander is absent would pass on an empty list, which a broken label filter
        // returning nothing would also produce.
        assertThat(managed).extracting(container -> container.getId()).contains(managedOne);
        assertThat(managed).noneSatisfy(container -> assertThat(container.getId()).isEqualTo(bystander));
        remove(bystander);
        remove(managedOne);
    }

    private String createContainer(Map<String, String> labels) {
        return SandboxTestSupport.docker()
                .createContainerCmd(SandboxTestSupport.probeImage())
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("none").withAutoRemove(false))
                .withCmd(List.of("sleep", "120"))
                .withLabels(labels)
                .exec()
                .getId();
    }

    private boolean containerExists(String id) {
        try {
            SandboxTestSupport.docker().inspectContainerCmd(id).exec();
            return true;
        } catch (RuntimeException gone) {
            return false;
        }
    }

    private void remove(String id) {
        try {
            SandboxTestSupport.docker().removeContainerCmd(id).withForce(true).exec();
        } catch (RuntimeException alreadyGone) {
            // Nothing to clean up.
        }
    }
}
