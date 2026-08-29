package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Removes sandboxes a previous launcher left behind.
 *
 * <p>A launcher can die between starting a sandbox and cleaning it up — a crash, a kill, a host reboot — and
 * the sandbox outlives it. Without reconciliation those accumulate: disk, memory, and in a future slice a
 * still-running copy of untrusted code that nothing is watching. The cost is not theoretical: a single orphan
 * flooding stdout was measured writing to host disk at 883 MB/s, about sixteen minutes from filling it.
 *
 * <p>The safety property that matters more than the cleanup is that this <em>only ever touches containers
 * KaaS created</em>. It matches on a label the launcher sets and nothing else — never a name prefix, never an
 * image match, never "everything stopped". A reconciler that guesses is a reconciler that one day deletes a
 * developer's database.
 */
public final class OrphanSandboxReconciler {
    /**
     * How far past its own deadline a sandbox must be before another launcher will reclaim it.
     *
     * <p>Generous on purpose. The window only has to exceed the longest legitimate run plus the time a healthy
     * launcher takes to clean up after it; being early here means destroying live work, which is the failure
     * this class exists to avoid.
     */
    private static final Duration ABANDONMENT_GRACE = Duration.ofMinutes(5);

    private final DockerClient docker;
    private final String currentGeneration;
    private final Duration wallClockTimeout;
    private final Clock clock;

    public OrphanSandboxReconciler(
            DockerClient docker, String currentGeneration, Duration wallClockTimeout) {
        this(docker, currentGeneration, wallClockTimeout, Clock.systemUTC());
    }

    OrphanSandboxReconciler(
            DockerClient docker, String currentGeneration, Duration wallClockTimeout, Clock clock) {
        this.docker = docker;
        this.currentGeneration = currentGeneration;
        this.wallClockTimeout = wallClockTimeout;
        this.clock = clock;
    }

    /**
     * Removes every KaaS-managed sandbox that has demonstrably been abandoned.
     *
     * <p>Abandonment is judged by age, not by generation. Scoping by generation alone was unsafe in the
     * direction this class cares most about: it force-removed <em>running</em> containers belonging to a live
     * sibling launcher, which is the opposite of what its own documentation promised, and it was observed
     * destroying another process's sandboxes mid-run. Age is the only signal available here that distinguishes
     * "nobody is coming back for this" from "somebody is using this right now", because a launcher's liveness
     * is not something another process can see.
     *
     * <p>A container younger than the deadline plus the grace window is left alone whoever created it. Past
     * that point no launcher can still be legitimately waiting on it — its own deadline would have fired long
     * before — so it is reclaimed regardless of generation. That also closes the case generation-scoping could
     * never reach: a sandbox orphaned by <em>this</em> generation, which nothing previously reclaimed.
     *
     * @return how many orphans were removed
     */
    public int reconcile() {
        long abandonedBefore = clock.instant().minus(wallClockTimeout).minus(ABANDONMENT_GRACE).getEpochSecond();
        int removed = 0;
        for (Container container : managedContainers()) {
            Map<String, String> labels = container.getLabels();
            if (labels == null) {
                continue;
            }
            Long created = container.getCreated();
            if (created == null || created > abandonedBefore) {
                // Young enough that a launcher could still be waiting on it. Never removed, whoever owns it.
                continue;
            }
            try {
                docker.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
                removed++;
            } catch (RuntimeException alreadyGone) {
                // Another reconciler won the race. Not a failure: the orphan is gone either way.
            }
        }
        return removed;
    }

    /** Which generation this reconciler belongs to, recorded for evidence rather than used for safety. */
    public String currentGeneration() {
        return currentGeneration;
    }

    /** Every container carrying the managed label, running or not. Nothing else is ever considered. */
    public List<Container> managedContainers() {
        return docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(SandboxLabels.MANAGED, "true"))
                .exec();
    }
}
