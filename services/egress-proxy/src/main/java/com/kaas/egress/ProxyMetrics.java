package com.kaas.egress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters, keyed only by values from closed enumerations.
 *
 * <p>Cardinality is a security property here, not just an operational one. A run identifier, a hostname, or a
 * resolved address used as a label would put tenant data into a metrics store that is read across tenants and
 * retained far longer than a log. The only dimensions are {@link DenialReason} and {@link AddressClass}, both
 * of which are fixed sets.
 */
public final class ProxyMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private final AtomicLong activeConnections = new AtomicLong();

    public void authorizationRequested() {
        increment("kaas_egress_authorization_total");
    }

    public void denied(DenialReason reason) {
        increment("kaas_egress_denied_total{reason=" + reason.name() + "}");
    }

    public void addressRefused(AddressClass addressClass) {
        increment("kaas_egress_address_refused_total{class=" + addressClass.name() + "}");
    }

    public void tunnelRevoked() {
        increment("kaas_egress_tunnel_revoked_total");
    }

    public long connectionOpened() {
        return activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public long activeConnections() {
        return activeConnections.get();
    }

    public long count(String name) {
        AtomicLong counter = counters.get(name);
        return counter == null ? 0 : counter.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new java.util.TreeMap<>();
        counters.forEach((name, value) -> snapshot.put(name, value.get()));
        snapshot.put("kaas_egress_active_connections", activeConnections.get());
        return snapshot;
    }

    private void increment(String name) {
        counters.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
    }
}
