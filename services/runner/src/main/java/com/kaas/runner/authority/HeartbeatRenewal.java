package com.kaas.runner.authority;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.client.ControlPlaneUnavailable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns one heartbeat call into one classified authority decision.
 *
 * <p>Separate from the monitor because the monitor's job is the budget arithmetic and this one's is reading
 * an answer. Keeping them apart is what lets the budget be tested with no HTTP at all.
 */
public final class HeartbeatRenewal implements ExecutionAuthorityMonitor.RenewalSource {

    private final ControlPlaneClient controlPlane;
    private final ObjectMapper mapper;
    private final UUID runId;
    private final UUID attemptId;
    private final String body;
    private final Duration timeout;

    public HeartbeatRenewal(
            ControlPlaneClient controlPlane,
            ObjectMapper mapper,
            UUID runId,
            UUID attemptId,
            String body,
            Duration timeout) {
        this.controlPlane = controlPlane;
        this.mapper = mapper;
        this.runId = runId;
        this.attemptId = attemptId;
        this.body = body;
        this.timeout = timeout;
    }

    /**
     * Asks once, and never throws.
     *
     * <p>A throw here would end the monitor thread, and a stopped monitor is a workload with nothing watching
     * it — the precise failure this mechanism exists to prevent. Everything that can go wrong becomes a
     * decision instead: unreachable and unreadable both consume budget rather than deciding anything.
     */
    @Override
    public ExecutionAuthorityMonitor.Renewal renew() {
        ControlPlaneClient.Response response;
        try {
            response = controlPlane.renewLease(runId, attemptId, body, timeout);
        } catch (ControlPlaneUnavailable | RuntimeException unreachable) {
            return ExecutionAuthorityMonitor.Renewal.unavailable();
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (RuntimeException unreadable) {
            // An answer arrived and could not be understood. Not treated as a refusal — a truncated or
            // rewritten body is a transport problem wearing a response's clothes — so it consumes budget.
            return ExecutionAuthorityMonitor.Renewal.unavailable();
        }
        JsonNode decision = root == null ? null : root.get("decision");
        AuthorityDecision classified = AuthorityDecision.fromReason(
                decision == null || !decision.isString() ? null : decision.stringValue());
        return new ExecutionAuthorityMonitor.Renewal(classified, leaseWindow(root));
    }

    /**
     * How long the lease has left, computed entirely inside the database's clock domain.
     *
     * <p>The two instants are subtracted from each other and never from anything local. Comparing
     * {@code leaseExpiresAt} against this host's wall clock would make the budget depend on the difference
     * between two machines' NTP corrections — a difference that is invisible, varies over time, and can be
     * either sign. What crosses the boundary is a duration, which means the same thing on both hosts.
     *
     * @return the remaining window, or null when the answer carried none
     */
    private static Duration leaseWindow(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode serverNow = root.get("serverNow");
        JsonNode expiresAt = root.get("leaseExpiresAt");
        if (serverNow == null || expiresAt == null || !serverNow.isString() || !expiresAt.isString()) {
            return null;
        }
        try {
            return Duration.between(
                    Instant.parse(serverNow.stringValue()), Instant.parse(expiresAt.stringValue()));
        } catch (RuntimeException unparseable) {
            return null;
        }
    }
}
