package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Network;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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

    /**
     * What this reconciler has reclaimed, by resource kind and nothing else.
     *
     * <p>Worth counting rather than merely returning: a reclaimed proxy is a gateway that outlived its
     * execution, and one appearing on a healthy host is a defect somewhere upstream. The return value tells
     * one caller how many; the counter is what makes a slow leak visible before anyone goes looking.
     */
    private final EgressMetrics metrics;

    public OrphanSandboxReconciler(
            DockerClient docker, String currentGeneration, Duration wallClockTimeout) {
        this(docker, currentGeneration, wallClockTimeout, Clock.systemUTC(), new EgressMetrics());
    }

    OrphanSandboxReconciler(
            DockerClient docker, String currentGeneration, Duration wallClockTimeout, Clock clock) {
        this(docker, currentGeneration, wallClockTimeout, clock, new EgressMetrics());
    }

    public OrphanSandboxReconciler(
            DockerClient docker,
            String currentGeneration,
            Duration wallClockTimeout,
            Clock clock,
            EgressMetrics metrics) {
        this.docker = docker;
        this.currentGeneration = currentGeneration;
        this.wallClockTimeout = wallClockTimeout;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** What this reconciler has reclaimed. Categories only; see {@link EgressMetrics}. */
    public EgressMetrics metrics() {
        return metrics;
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
        // Containers first, then networks. A network with an endpoint still attached cannot be removed, so
        // reconciling networks before the containers on them would leave every one of them behind and report
        // success — the reconciler would appear to work while accumulating exactly the resource it is meant
        // to reclaim.
        int removed = reconcileContainers();
        removed += reconcileNetworks();
        return removed;
    }

    /**
     * Removes abandoned KaaS-managed containers — sandboxes and egress proxies alike.
     *
     * <p>Both carry the same managed label and are judged by the same rule, which is deliberate. A reconciler
     * that removed sandboxes and skipped proxies would leave a running egress gateway with no execution behind
     * it: a container still holding a service credential, still attached to the target network, and now with
     * nothing at all that could stop it. That is the single worst artefact this component could leave.
     */
    private int reconcileContainers() {
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
                if (SandboxLabels.RESOURCE_PROXY.equals(labels.get(SandboxLabels.RESOURCE))) {
                    metrics.proxyReconciled();
                }
            } catch (RuntimeException alreadyGone) {
                // Another reconciler won the race. Not a failure: the orphan is gone either way.
            }
        }
        return removed;
    }

    /**
     * Removes abandoned per-execution networks.
     *
     * <p>Three conditions, all required, and none of them is "unused".
     *
     * <ul>
     *   <li><strong>Labelled by this platform</strong> as a per-execution network. Never a name prefix and
     *       never a scan of what looks unused: {@code docker network prune} would take a developer's
     *       compose network, another tool's network, and anything created a moment ago by a process this one
     *       cannot see. The label is a statement of ownership; nothing else is.
     *   <li><strong>Empty.</strong> A network with an endpoint still attached belongs to something that is
     *       still running, whatever its age says. The daemon refuses to remove such a network too, so this
     *       check is not the only thing standing between a live execution and losing its network — it is
     *       stated here so the rule does not depend on an exception thrown by another process, and it is
     *       honest to record that its mutation coverage is joint with the daemon's own refusal rather than
     *       independent.
     *   <li><strong>Older than the abandonment window.</strong> A network is created before anything joins
     *       it, so a newly created one is legitimately empty for a moment. Without the age check a reconciler
     *       would race a launcher and delete the network out from under an execution that was starting.
     * </ul>
     */
    private int reconcileNetworks() {
        Instant abandonedBefore = clock.instant().minus(wallClockTimeout).minus(ABANDONMENT_GRACE);
        int removed = 0;
        for (Network network : managedNetworks()) {
            Map<String, String> labels = network.getLabels();
            if (labels == null || !SandboxLabels.RESOURCE_NETWORK.equals(labels.get(SandboxLabels.RESOURCE))) {
                continue;
            }
            Network inspected;
            try {
                // Listing does not populate the attached-container map on every daemon version, and treating
                // an unpopulated map as "empty" would mean deleting live networks. Inspect says so directly.
                inspected = docker.inspectNetworkCmd().withNetworkId(network.getId()).exec();
            } catch (RuntimeException gone) {
                continue;
            }
            if (inspected.getContainers() != null && !inspected.getContainers().isEmpty()) {
                continue;
            }
            Date created = inspected.getCreated();
            if (created == null || !created.toInstant().isBefore(abandonedBefore)) {
                continue;
            }
            try {
                docker.removeNetworkCmd(network.getId()).exec();
                removed++;
                metrics.networkReconciled();
            } catch (RuntimeException alreadyGone) {
                // Another reconciler won the race, or a container joined between the check and the removal.
                // The daemon refuses to remove a network in use, so losing this race is safe by construction.
            }
        }
        return removed;
    }

    /** Every network carrying the managed label. Nothing else is ever considered. */
    public List<Network> managedNetworks() {
        return docker.listNetworksCmd().exec().stream()
                .filter(network -> network.getLabels() != null
                        && "true".equals(network.getLabels().get(SandboxLabels.MANAGED)))
                .toList();
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
