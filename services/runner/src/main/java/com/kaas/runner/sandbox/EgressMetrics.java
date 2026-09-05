package com.kaas.runner.sandbox;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runner-side egress counters, keyed only by values from closed enumerations.
 *
 * <h2>Cardinality is a security property here, not only an operational one</h2>
 *
 * <p>A metrics store is read across tenants and retained far longer than a log. A run identifier, an
 * attempt, an epoch, a tenant, a destination hostname, or a resolved address used as a label would put tenant
 * data there permanently. So there is no method on this class that accepts a free string: the only dimension
 * any counter carries is an {@link EgressFailure}, which is a fixed set, and the type is what enforces that
 * rather than a convention somebody has to remember.
 *
 * <h2>Why it is hand-rolled</h2>
 *
 * <p>The same reason the proxy's own counters are: this module has no framework. Bringing a metrics library
 * into the trusted launcher to count six things would widen the dependency surface of the one component that
 * holds daemon access, and the build guard that keeps that surface narrow is what lets it hold one at all.
 */
public final class EgressMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** A proxy came up and an execution ran behind it. */
    public void proxyLaunched() {
        increment("kaas_egress_proxy_launch_total");
    }

    /** A proxy did not come up, categorised by which part of the mechanism failed. */
    public void proxyFailed(EgressFailure failure) {
        increment("kaas_egress_proxy_failure_total{reason=" + failure.name() + "}");
    }

    /** A proxy that outlived its execution was reclaimed. Never zero for long on a healthy host. */
    public void proxyReconciled() {
        increment("kaas_egress_reconciliation_total{resource=" + SandboxLabels.RESOURCE_PROXY + "}");
    }

    /** A per-execution network that outlived its execution was reclaimed. */
    public void networkReconciled() {
        increment("kaas_egress_reconciliation_total{resource=" + SandboxLabels.RESOURCE_NETWORK + "}");
    }

    public long count(String name) {
        AtomicLong counter = counters.get(name);
        return counter == null ? 0 : counter.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new TreeMap<>();
        counters.forEach((name, value) -> snapshot.put(name, value.get()));
        return snapshot;
    }

    private void increment(String name) {
        counters.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
    }
}
