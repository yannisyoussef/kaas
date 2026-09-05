package com.kaas.api.execution.application;

import com.kaas.api.controlplane.application.WorkerLeaseRepository;
import com.kaas.api.controlplane.domain.ExecutionAttemptState;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.execution.domain.CapabilityToken;
import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.EgressDestination;
import com.kaas.api.execution.domain.EgressScheme;
import com.kaas.api.execution.domain.NetworkPolicyRevision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers, for one presented egress capability and one destination, whether traffic may go there now.
 *
 * <h2>Called constantly, and authoritative every time</h2>
 *
 * <p>This runs on every proxied request and again on every revalidation of every open tunnel. It would be
 * tempting to make it cheap by trusting the token's own expiry, and that would defeat the entire model: a
 * capability issued a second before a cancellation has an unexpired TTL. Expiry bounds the damage from a
 * leaked token; this method is what makes fencing effective. It revalidates the same authoritative state the
 * source redemption path does, under the same lock, in the same order.
 *
 * <p>Nothing is written. An egress validation delivers nothing and consumes no redemption — the database has a
 * constraint that says so, because wiring this into the redemption path would hit the amplification ceiling
 * within seconds of a healthy execution starting and fence it for reasons nobody could reconstruct.
 *
 * <h2>What the answer may say</h2>
 *
 * <p>The caller is the egress proxy, which sits on a network an untrusted sandbox can reach. So the response
 * is a verdict and a category, and never a run identifier, a policy's contents, a worker's identity, or an
 * expiry instant. A proxy that never receives those cannot disclose them.
 */
@Service
public class EgressAuthorizationService {

    private final ExecutionAuthorizationRepository repository;

    private final WorkerLeaseRepository leases;

    private final MeterRegistry meters;

    public EgressAuthorizationService(
            ExecutionAuthorizationRepository repository, WorkerLeaseRepository leases, MeterRegistry meters) {
        this.repository = repository;
        this.leases = leases;
        this.meters = meters;
    }

    /** The narrow decision the proxy receives. */
    public enum Decision {
        AUTHORIZED,
        /** The destination is not in the policy this execution was sealed with. */
        DENIED_POLICY,
        /** The assignment behind the capability is no longer the live one. */
        DENIED_FENCED,
        /** The capability's own window has closed, or the authorization's has. */
        DENIED_EXPIRED,
        /** The presented credential does not identify a live egress capability at all. */
        DENIED_CAPABILITY,
        /** The run is no longer in a state that may produce traffic. */
        DENIED_STATE
    }

    /**
     * @param presentedToken the opaque bearer credential exactly as the sandbox presented it to the proxy
     * @param host canonical host, as specified in the egress canonicalization contract
     * @param port explicit port
     * @param scheme HTTP or HTTPS
     */
    // Deliberately NOT readOnly. This method writes nothing, and marking it read-only was the obvious way to
    // say so — but it takes the run's row lock, and PostgreSQL refuses SELECT ... FOR UPDATE in a read-only
    // transaction. The result was a 500 on every request, which the proxy correctly read as
    // AUTHORIZATION_UNAVAILABLE and correctly refused. Fail-closed worked; nothing would have worked at all.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Decision authorize(String presentedToken, String host, int port, String scheme) {
        if (!CapabilityToken.hasShapeOf(presentedToken, CapabilityType.EGRESS)) {
            // Refused on shape before anything is looked up, so a source token presented here is never
            // searched for in the egress population and a malformed one costs no query.
            return refused(Decision.DENIED_CAPABILITY);
        }

        EgressDestination destination;
        try {
            // Parsed with the control plane's own canonicalizer, independently of the proxy's. If the two
            // disagree about what the request named, this refuses — which is the safe direction, and the
            // contract test between the two implementations exists so that it does not happen quietly.
            destination = new EgressDestination(host, port, EgressScheme.valueOf(scheme));
        } catch (IllegalArgumentException notCanonical) {
            return refused(Decision.DENIED_POLICY);
        }

        var found = repository.findRedeemable(CapabilityToken.hash(presentedToken), CapabilityType.EGRESS);
        if (found.isEmpty()) {
            return refused(Decision.DENIED_CAPABILITY);
        }
        var capability = found.orElseThrow().capability();
        var authorization = found.orElseThrow().authorization();

        // The lock first, and the clock read under it. Reading the clock before taking the lock was a real
        // defect on the source path: every window check was evaluated against an instant from before the wait
        // on a contended run row, and a capability was demonstrably served after it had expired.
        var locked = leases.lockOwnedByRun(authorization.runId());
        if (locked.isEmpty()) {
            return refused(Decision.DENIED_FENCED);
        }
        Instant now = repository.currentDatabaseTime();
        if (!capability.withinWindow(now) || !authorization.withinWindow(now)) {
            return refused(Decision.DENIED_EXPIRED);
        }

        var run = locked.orElseThrow().run();
        var attempt = locked.orElseThrow().attempt();
        if (!run.lifecycleState().mayProduceExecutionEgress()) {
            // A run that is stopping, settled, or past the point where its sandbox exists may not produce
            // traffic, whatever its capability's TTL still says. The rule lives on the lifecycle rather than
            // here: restating it as `!= CLAIMED` denied every request a real execution ever made, because an
            // executing run is in PROVISIONING or RUNNING and never in CLAIMED. Nothing noticed until an
            // allowlist execution was actually run end to end.
            return refused(Decision.DENIED_STATE);
        }
        if (attempt.state() != ExecutionAttemptState.CLAIMED
                || !attempt.assignment().isHeldBy(authorization.workerId(), authorization.assignmentEpoch())
                || !authorization.describes(
                        attempt.attemptId(), attempt.assignment().epoch(), authorization.workerId())) {
            // The assignment moved: a different worker, a later epoch, or a fenced attempt. The capability's
            // basis is gone in all three cases.
            return refused(Decision.DENIED_FENCED);
        }
        if (attempt.assignment().expiredAt(now)) {
            // Lease lapsed but not yet fenced by the reconciler. Authorizing here would carry traffic on the
            // strength of an assignment that has already ended.
            return refused(Decision.DENIED_FENCED);
        }

        Optional<NetworkPolicyRevision> policy =
                repository.findNetworkPolicy(authorization.networkPolicyRevisionId());
        if (policy.isEmpty() || !policy.orElseThrow().digestMatchesContent()) {
            // A policy whose digest no longer matches its own content has been altered underneath the
            // authorization that named it. Refusing is the only safe reading.
            return refused(Decision.DENIED_POLICY);
        }
        if (!policy.orElseThrow().permits(destination)) {
            return refused(Decision.DENIED_POLICY);
        }

        count("kaas.egress.authorization", "AUTHORIZED");
        return Decision.AUTHORIZED;
    }

    private Decision refused(Decision decision) {
        count("kaas.egress.authorization", decision.name());
        return decision;
    }

    /**
     * Counted by category only.
     *
     * <p>No run, attempt, capability, tenant, hostname, or address becomes a metric dimension. A metrics store
     * is read across tenants and retained far longer than a log, and this method is called often enough that
     * a high-cardinality label here would be both a disclosure and an outage.
     */
    private void count(String name, String result) {
        Counter.builder(name).tag("result", result).register(meters).increment();
    }
}
