package com.kaas.api.execution.application;

import com.kaas.api.controlplane.application.WorkerLeaseRepository;
import com.kaas.api.controlplane.domain.ExecutionAttemptState;
import com.kaas.api.controlplane.domain.PinnedSecretBinding;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.execution.domain.CapabilityToken;
import com.kaas.api.execution.domain.CapabilityType;
import com.kaas.api.execution.domain.ExecutionAuthorization;
import com.kaas.api.execution.domain.ExecutionCapability;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.kaas.api.execution.domain.SecretBundleFormat;
import com.kaas.api.secrets.domain.SecretFailure;
import com.kaas.api.secrets.domain.SecretLimits;
import com.kaas.api.secrets.domain.SecretProviderException;
import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.TransitContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redeems a secret capability for the exact, pinned secret values one live assignment was authorized to receive.
 *
 * <h2>The only path on which the platform decrypts</h2>
 *
 * <p>Nothing else in the control plane calls Transit decrypt: not run creation, not queueing, not dispatch, not
 * command issuance. Plaintext exists in this process only between the decryption below and the response that
 * carries it, and only for a worker presenting a capability issued to it for this assignment.
 *
 * <h2>Three steps, and why the middle one is outside any transaction</h2>
 *
 * <ol>
 *   <li><strong>Authority, under the run lock.</strong> The capability is looked up by hash and every fact it
 *       stands on is re-read: run still CLAIMED, the attempt held by this worker at this epoch and unfenced, the
 *       lease unexpired, the authorization and the capability within their windows. The redemption is counted
 *       here, against a ceiling of two per capability and four per authorization. The scope is compared with the
 *       snapshot's pinned set, exactly; revocation and shredding are checked. A capability whose basis has gone
 *       gets nothing, and Vault is never called for it.</li>
 *   <li><strong>Decryption, with no lock held.</strong> Holding the run's row lock across a network call to the
 *       key service would let a slow provider stall cancellation, heartbeats and the reconciler for that run.
 *       Every value is decrypted under its own tenant's derived context, with a bounded timeout; any failure
 *       discards everything decrypted so far. All or nothing — a partial bundle is never sent.</li>
 *   <li><strong>Authority again, under the lock.</strong> The decryption took time, and a run can be cancelled
 *       or a lease lost during it. So the same facts are re-read, and a version revoked while the call was in
 *       flight is caught too. If anything moved, the plaintext is cleared and nothing is delivered.</li>
 * </ol>
 *
 * <p>The residual window after step three — between the last check and the bytes leaving the socket — is the
 * same one every capability redemption in this system has, and it is closed on the runner side: a worker whose
 * authority ends stops its sandbox, and the engine does not start until the whole bundle has been received.
 *
 * <h2>What is never done</h2>
 *
 * <p>No value is cached, logged, counted, or put in an exception. The response is a binary frame the caller
 * writes and clears. Failures leave as {@link ExecutionDenial} categories.
 */
@Service
public class SecretCapabilityService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecretCapabilityService.class);

    /**
     * Secret redemptions permitted under one authorization, across every capability rotation.
     *
     * <p>Each re-authorization rotates the secret capability and a fresh capability has its own two
     * redemptions, so without this a worker could re-authorize in a loop and multiply them. Four is two
     * deliveries' worth: an ordinary lost response on each of the two exchanges and nothing more.
     */
    static final int MAX_REDEMPTIONS_PER_AUTHORIZATION = 4;

    private final ExecutionAuthorizationRepository repository;
    private final WorkerLeaseRepository leases;
    private final SecretTransit transit;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;

    public SecretCapabilityService(
            ExecutionAuthorizationRepository repository,
            WorkerLeaseRepository leases,
            SecretTransit transit,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters) {
        this.repository = repository;
        this.leases = leases;
        this.transit = transit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.meters = meters;
    }

    /**
     * Exchanges a presented token for the secret bundle it authorizes.
     *
     * @param workerId the authenticated service principal, never a value from the request body
     */
    public Redemption redeem(String presentedToken, String workerId) {
        Timer.Sample timing = Timer.start(meters);
        try {
            if (!CapabilityToken.hasShapeOf(presentedToken, CapabilityType.SECRET)) {
                // On shape, before anything is looked up: a source or egress token presented here is never
                // searched for, and a malformed one costs no query.
                return refused(ExecutionDenial.CAPABILITY_INVALID, null);
            }
            Checked checked = transactions.execute(status -> authorityAndScope(presentedToken, workerId));
            if (checked.denial() != null) {
                return refused(checked.denial(), checked.redeemable());
            }

            List<byte[]> values = new ArrayList<>();
            try {
                for (var material : checked.material()) {
                    // The context comes from the OWNERSHIP COLUMNS of the row being decrypted, never from the
                    // request and never from a different row. A ciphertext joined to the wrong tenant fails the
                    // authentication tag rather than decrypting.
                    values.add(transit.decrypt(
                            TransitContext.of(material.organizationId(), material.projectId()),
                            material.ciphertext()));
                }
                ExecutionDenial oversized = boundsViolation(values);
                if (oversized != null) {
                    return refused(oversized, checked.redeemable());
                }

                ExecutionDenial lost = transactions.execute(status -> stillAuthorized(checked, workerId));
                if (lost != null) {
                    return refused(lost, checked.redeemable());
                }

                List<SecretBundleFormat.Entry> entries = new ArrayList<>();
                for (int index = 0; index < values.size(); index++) {
                    entries.add(new SecretBundleFormat.Entry(checked.material().get(index).bindingKey(), values.get(index)));
                }
                byte[] frame = SecretBundleFormat.encode(entries);
                count("GRANTED");
                LOGGER.atInfo()
                        .addKeyValue("event", "SECRET_BUNDLE_REDEEMED")
                        .addKeyValue("runId", checked.redeemable().authorization().runId())
                        .addKeyValue("attemptId", checked.redeemable().authorization().attemptId())
                        .addKeyValue("assignmentEpoch", checked.redeemable().authorization().assignmentEpoch())
                        .addKeyValue("capabilityId", checked.redeemable().capability().capabilityId())
                        .addKeyValue("secretCount", entries.size())
                        .log("Released the pinned secret set to an active assignment");
                return new Redemption(Optional.of(new Bundle(frame)), Optional.empty());
            } catch (SecretProviderException failed) {
                return refused(denialFor(failed.failure()), checked.redeemable());
            } finally {
                values.forEach(value -> Arrays.fill(value, (byte) 0));
            }
        } finally {
            timing.stop(Timer.builder("kaas.secret.resolution.latency").register(meters));
        }
    }

    /** Step one: every authority fact, the redemption count, and the exact scope. Runs under the run lock. */
    private Checked authorityAndScope(String presentedToken, String workerId) {
        var found = repository.findRedeemable(CapabilityToken.hash(presentedToken), CapabilityType.SECRET);
        if (found.isEmpty()) {
            return Checked.denied(ExecutionDenial.CAPABILITY_INVALID, null);
        }
        var redeemable = found.orElseThrow();
        ExecutionCapability capability = redeemable.capability();
        ExecutionAuthorization authorization = redeemable.authorization();

        // Lock first, clock second: the same order every writer in this system uses, for the reason the source
        // redemption documents -- an instant read before a blocking lock is stale by the length of the wait.
        var locked = leases.lockOwnedByRun(authorization.runId());
        if (locked.isEmpty()) {
            return Checked.denied(ExecutionDenial.CAPABILITY_FENCED, redeemable);
        }
        Instant now = repository.currentDatabaseTime();
        if (!capability.withinWindow(now) || !authorization.withinWindow(now)) {
            return Checked.denied(ExecutionDenial.CAPABILITY_EXPIRED, redeemable);
        }
        var run = locked.orElseThrow().run();
        var attempt = locked.orElseThrow().attempt();
        if (run.lifecycleState() != RunLifecycle.CLAIMED
                || attempt.state() != ExecutionAttemptState.CLAIMED
                || !attempt.assignment().isHeldBy(workerId, authorization.assignmentEpoch())
                || !authorization.describes(attempt.attemptId(), attempt.assignment().epoch(), workerId)
                || attempt.assignment().expiredAt(now)) {
            // Cancelled, completed, fenced, superseded, handed to another worker, or lapsed. One answer for all
            // of them, and Vault is never called.
            return Checked.denied(ExecutionDenial.CAPABILITY_FENCED, redeemable);
        }
        if (repository.secretRedemptionsUnder(authorization.authorizationId()) >= MAX_REDEMPTIONS_PER_AUTHORIZATION
                || !repository.recordRedemption(capability.capabilityId(), now)) {
            return Checked.denied(ExecutionDenial.CAPABILITY_EXPIRED, redeemable);
        }

        List<ExecutionAuthorizationRepository.SecretMaterial> material =
                repository.loadSecretMaterial(capability.capabilityId());
        var snapshot = repository.loadSnapshot(
                locked.orElseThrow().organizationId(), run.projectId(), run.runId());
        if (snapshot.isEmpty() || !exactlyTheSnapshot(material, snapshot.orElseThrow().secretBindings())) {
            // The scope was written at issuance from this snapshot, so a disagreement is a defect or tampering,
            // never a state to be reconciled by delivering whichever of the two sets is larger.
            return Checked.denied(ExecutionDenial.CAPABILITY_INVALID, redeemable);
        }
        for (var secret : material) {
            if (!secret.organizationId().equals(authorization.organizationId())
                    || !secret.projectId().equals(authorization.projectId())) {
                return Checked.denied(ExecutionDenial.CAPABILITY_INVALID, redeemable);
            }
        }
        if (material.stream().anyMatch(ExecutionAuthorizationRepository.SecretMaterial::revoked)) {
            return Checked.denied(ExecutionDenial.SECRET_VERSION_REVOKED, redeemable);
        }
        if (material.stream().anyMatch(secret -> secret.ciphertext() == null)) {
            return Checked.denied(ExecutionDenial.SECRET_VERSION_NOT_FOUND, redeemable);
        }
        return new Checked(null, redeemable, material);
    }

    /**
     * The scope and the snapshot name the same (key, reference, version) triples: none extra, none missing,
     * no key twice, and not empty.
     */
    static boolean exactlyTheSnapshot(
            List<ExecutionAuthorizationRepository.SecretMaterial> material, List<PinnedSecretBinding> pinned) {
        if (material.isEmpty() || material.size() != pinned.size()
                || material.size() > SecretLimits.MAX_SECRETS_PER_RUN) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        Set<String> scope = new HashSet<>();
        for (var secret : material) {
            if (!keys.add(secret.bindingKey())) {
                return false;
            }
            scope.add(secret.bindingKey() + "\u0000" + secret.secretReferenceId() + "\u0000" + secret.version());
        }
        Set<String> expected = new HashSet<>();
        for (var binding : pinned) {
            if (binding.version() == null) {
                return false;
            }
            expected.add(binding.key() + "\u0000" + binding.secretReferenceId() + "\u0000" + binding.version());
        }
        return scope.equals(expected);
    }

    /** Step three: the same facts, re-read after the provider call. Null means still authorized. */
    private ExecutionDenial stillAuthorized(Checked checked, String workerId) {
        ExecutionAuthorization issued = checked.redeemable().authorization();
        var locked = leases.lockOwnedByRun(issued.runId());
        if (locked.isEmpty()) {
            return ExecutionDenial.CAPABILITY_FENCED;
        }
        Instant now = repository.currentDatabaseTime();
        var current = repository.findAuthorization(issued.attemptId(), issued.assignmentEpoch());
        if (current.isEmpty() || !current.orElseThrow().withinWindow(now)
                || !repository.capabilityUnrevoked(checked.redeemable().capability().capabilityId())
                || !now.isBefore(checked.redeemable().capability().expiresAt())) {
            return ExecutionDenial.CAPABILITY_EXPIRED;
        }
        var run = locked.orElseThrow().run();
        var attempt = locked.orElseThrow().attempt();
        if (run.lifecycleState() != RunLifecycle.CLAIMED
                || attempt.state() != ExecutionAttemptState.CLAIMED
                || !attempt.assignment().isHeldBy(workerId, issued.assignmentEpoch())
                || attempt.assignment().expiredAt(now)) {
            return ExecutionDenial.CAPABILITY_FENCED;
        }
        var states = repository.pinnedVersionStates(
                issued.organizationId(),
                issued.projectId(),
                checked.material().stream()
                        .map(secret -> new PinnedSecretBinding(
                                secret.bindingKey(), secret.secretReferenceId(), secret.version()))
                        .toList());
        if (states.contains(ExecutionAuthorizationRepository.PinnedVersionState.REVOKED)) {
            // Revoked while the provider call was in flight. Revocation is an emergency control, so it wins
            // over a decryption that has already happened.
            return ExecutionDenial.SECRET_VERSION_REVOKED;
        }
        if (states.contains(ExecutionAuthorizationRepository.PinnedVersionState.MISSING)) {
            return ExecutionDenial.SECRET_VERSION_NOT_FOUND;
        }
        return null;
    }

    /** Bounds on what came back from the provider, not merely on what was stored: see {@link SecretLimits}. */
    private static ExecutionDenial boundsViolation(List<byte[]> values) {
        long total = 0;
        for (byte[] value : values) {
            if (value.length == 0) {
                return ExecutionDenial.SECRET_VALUE_INVALID;
            }
            if (value.length > SecretLimits.MAX_VALUE_BYTES) {
                return ExecutionDenial.SECRET_VALUE_TOO_LARGE;
            }
            total += value.length;
        }
        return total > SecretLimits.MAX_TOTAL_BYTES ? ExecutionDenial.SECRET_VALUE_TOO_LARGE : null;
    }

    static ExecutionDenial denialFor(SecretFailure failure) {
        return switch (failure) {
            case SECRET_PROVIDER_UNAVAILABLE -> ExecutionDenial.SECRET_PROVIDER_UNAVAILABLE;
            case SECRET_PROVIDER_AUTH_FAILED -> ExecutionDenial.SECRET_PROVIDER_AUTH_FAILED;
            case SECRET_ACCESS_DENIED -> ExecutionDenial.SECRET_ACCESS_DENIED;
            case SECRET_VALUE_INVALID -> ExecutionDenial.SECRET_VALUE_INVALID;
            case SECRET_VALUE_TOO_LARGE -> ExecutionDenial.SECRET_VALUE_TOO_LARGE;
            case SECRET_VERSION_REVOKED -> ExecutionDenial.SECRET_VERSION_REVOKED;
            case SECRET_VERSION_NOT_FOUND, SECRET_NOT_FOUND -> ExecutionDenial.SECRET_VERSION_NOT_FOUND;
            case SECRET_CAPABILITY_DENIED -> ExecutionDenial.CAPABILITY_INVALID;
            case SECRET_DELIVERY_FAILED -> ExecutionDenial.SECRET_DELIVERY_FAILED;
        };
    }

    private Redemption refused(ExecutionDenial denial, ExecutionAuthorizationRepository.Redeemable redeemable) {
        count(denial.name());
        var event = LOGGER.atInfo()
                .addKeyValue("event", "SECRET_BUNDLE_REFUSED")
                .addKeyValue("reason", denial.name());
        if (redeemable != null) {
            // Identifiers only, and only once the token has been found: a refusal of an unknown token has no
            // run to name, and naming one would turn this log line into a lookup oracle.
            event = event.addKeyValue("runId", redeemable.authorization().runId())
                    .addKeyValue("capabilityId", redeemable.capability().capabilityId());
        }
        event.log("Refused a secret bundle redemption");
        return new Redemption(Optional.empty(), Optional.of(denial));
    }

    private void count(String result) {
        Counter.builder("kaas.secret.redemption").tag("result", result).register(meters).increment();
    }

    /** What step one established: either a refusal, or the capability and its exact material. */
    private record Checked(
            ExecutionDenial denial,
            ExecutionAuthorizationRepository.Redeemable redeemable,
            List<ExecutionAuthorizationRepository.SecretMaterial> material) {
        static Checked denied(ExecutionDenial denial, ExecutionAuthorizationRepository.Redeemable redeemable) {
            return new Checked(denial, redeemable, List.of());
        }
    }

    /**
     * A framed secret bundle. The caller writes it once and then {@link #clear()}s it; nothing else holds it.
     *
     * <p>Not a record: a record's accessor would hand out the array, and its {@code toString} would print it.
     */
    public static final class Bundle {
        private final byte[] frame;

        Bundle(byte[] frame) {
            this.frame = frame;
        }

        /** The frame itself, for the one write that sends it. */
        public byte[] frame() {
            return frame;
        }

        public void clear() {
            Arrays.fill(frame, (byte) 0);
        }

        @Override
        public String toString() {
            return "Bundle[<secret>]";
        }
    }

    public record Redemption(Optional<Bundle> bundle, Optional<ExecutionDenial> denial) {}
}
