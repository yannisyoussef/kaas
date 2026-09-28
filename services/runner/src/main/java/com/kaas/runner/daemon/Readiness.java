package com.kaas.runner.daemon;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether this runner can safely accept a NEW assignment right now (KAAS-DEPLOY-001).
 *
 * <p>Readiness and liveness are different questions and are answered separately. Liveness is "is this process
 * healthy" -- it stays true through a control-plane outage, an expired token or a failed refresh, because each of
 * those is something the process recovers from on its own, and a restart would only interrupt the work it is
 * still doing. Readiness is "may it take more work", and every condition below must hold at once.
 *
 * <p>There is deliberately no condition about RabbitMQ. The runner does not consume it (ADR-035).
 */
public final class Readiness {

    /** Every condition a new assignment needs, in the order startup establishes them. */
    public enum Condition {
        /** The container runtime answers. */
        DOCKER,
        /** The configured sandbox runtime is registered and measured -- never a fallback to another. */
        RUNTIME,
        /** The digest-pinned probe, engine and (if allowlisting) proxy images are present on this host. */
        IMAGES,
        /** Evidence about THIS host's runtime exists, was accepted by the control plane, and is not expiring. */
        ATTESTATION,
        /** The runtime measured now is the one that evidence describes. */
        RUNTIME_DIGEST_MATCH,
        /** Startup orphan reconciliation finished. Never skipped, never assumed. */
        STARTUP_RECONCILIATION,
        /** A valid short-lived service credential can be had. */
        SERVICE_IDENTITY,
        /** The internal API answered this runner recently. */
        CONTROL_PLANE,
        /** Shutdown has not begun. The one condition that never comes back once lost. */
        NOT_DRAINING
    }

    private final Map<Condition, Boolean> state = new EnumMap<>(Condition.class);
    private final Map<Condition, String> reasons = new EnumMap<>(Condition.class);

    public Readiness() {
        for (Condition condition : Condition.values()) {
            state.put(condition, condition == Condition.NOT_DRAINING);
            reasons.put(condition, condition == Condition.NOT_DRAINING ? "OK" : "NOT_YET_ESTABLISHED");
        }
    }

    /** Records a condition, with a closed reason code when it does not hold. */
    public synchronized void set(Condition condition, boolean holds, String reason) {
        if (condition == Condition.NOT_DRAINING && holds && !state.get(Condition.NOT_DRAINING)) {
            // Draining is one-way. A runner that began shutting down must never become ready again.
            return;
        }
        boolean changed = state.put(condition, holds) != holds;
        reasons.put(condition, holds ? "OK" : reason);
        if (changed) {
            notifyAll();
        }
    }

    public void holds(Condition condition) {
        set(condition, true, "OK");
    }

    public synchronized void drain() {
        set(Condition.NOT_DRAINING, false, "DRAINING");
    }

    public synchronized boolean ready() {
        return state.values().stream().allMatch(Boolean::booleanValue);
    }

    public synchronized boolean draining() {
        return !state.get(Condition.NOT_DRAINING);
    }

    public synchronized boolean isHeld(Condition condition) {
        return state.get(condition);
    }

    /** Blocks until ready, draining, or the timeout passes. Interruptible. */
    public synchronized void awaitChange(long timeoutMillis) throws InterruptedException {
        if (!ready() && !draining()) {
            wait(timeoutMillis);
        }
    }

    /** Condition to status word, for the readiness endpoint. Reason codes only; nothing host-describing. */
    public synchronized Map<String, String> report() {
        Map<String, String> report = new LinkedHashMap<>();
        for (Condition condition : Condition.values()) {
            report.put(condition.name(), reasons.get(condition));
        }
        return report;
    }
}
