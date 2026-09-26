package com.kaas.api.secrets.application;

import com.kaas.api.secrets.domain.SecretFailure;
import com.kaas.api.secrets.domain.SecretProviderException;
import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.SecretVersion;
import com.kaas.api.secrets.domain.TransitContext;
import com.kaas.api.security.TenantPrincipal;
import com.kaas.api.shared.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes, lists and revokes the immutable versions of a tenant's secret.
 *
 * <h2>The one place plaintext enters the platform</h2>
 *
 * <p>A version write receives plaintext from an authenticated tenant, hands it to Transit, persists what Transit
 * returns, and clears its own copy. Nothing about the value is logged, counted, fingerprinted for idempotency,
 * echoed in the response, or attached to an exception. The response is metadata: the reference, the version
 * number, who created it and when.
 *
 * <p><strong>No idempotency key.</strong> Every other create in this API stores a fingerprint of the request so a
 * retry can be recognised, and for this request that fingerprint would be a hash of the plaintext — the guessing
 * oracle the schema refuses to hold. So a version write is simply not idempotent: a retried POST creates the next
 * version, which is harmless (versions are cheap, immutable, and a run pins the one it was created with) and is
 * stated here rather than discovered.
 *
 * <p><strong>Encryption happens outside the transaction.</strong> A Transit call is a network round trip, and
 * holding the reference's row lock across it would let a slow provider stall every other write to that
 * reference. The version number is allocated under the lock afterwards; the ciphertext does not depend on it.
 */
@Service
public class SecretVersionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecretVersionService.class);

    private final SecretVersionRepository versions;
    private final SecretTransit transit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry meters;

    public SecretVersionService(
            SecretVersionRepository versions,
            SecretTransit transit,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry meters) {
        this.versions = versions;
        this.transit = transit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Encrypts and stores the next version of a secret.
     *
     * <p>Takes ownership of {@code plaintext} and clears it on every path, including refusal.
     */
    public SecretVersion createVersion(
            TenantPrincipal principal, UUID projectId, UUID secretReferenceId, byte[] plaintext) {
        try {
            // Existence first, so a request for a reference that is not the caller's never costs a Transit call
            // and never learns anything from how long one took.
            Boolean exists = transactions.execute(status ->
                    versions.referenceExists(principal.organizationId(), projectId, secretReferenceId));
            if (!Boolean.TRUE.equals(exists)) {
                throw ApiException.notFound();
            }
            SecretTransit.EncryptedValue encrypted;
            try {
                encrypted = transit.encrypt(TransitContext.of(principal.organizationId(), projectId), plaintext);
            } catch (SecretProviderException refused) {
                count("kaas.secret.version.created", refused.failure().name());
                throw refusal(refused.failure());
            }
            Instant now = clock.instant();
            SecretVersion created = transactions.execute(status -> {
                if (!versions.lockReference(principal.organizationId(), projectId, secretReferenceId)) {
                    throw ApiException.notFound();
                }
                int next = versions.nextVersionNumber(secretReferenceId);
                return versions.insert(
                        principal.organizationId(), projectId, secretReferenceId, next, encrypted,
                        principal.principalId(), now);
            });
            count("kaas.secret.version.created", "CREATED");
            LOGGER.atInfo()
                    .addKeyValue("event", "SECRET_VERSION_CREATED")
                    .addKeyValue("organizationId", principal.organizationId())
                    .addKeyValue("projectId", projectId)
                    .addKeyValue("secretReferenceId", secretReferenceId)
                    .addKeyValue("version", created.version())
                    .log("Stored a new encrypted secret version");
            return created;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public List<SecretVersion> listVersions(TenantPrincipal principal, UUID projectId, UUID secretReferenceId) {
        return transactions.execute(status -> {
            if (!versions.referenceExists(principal.organizationId(), projectId, secretReferenceId)) {
                throw ApiException.notFound();
            }
            return versions.list(principal.organizationId(), projectId, secretReferenceId);
        });
    }

    /**
     * Revokes one version and destroys its ciphertext.
     *
     * <p>Idempotent in effect: revoking a revoked version returns it unchanged. There is no un-revoke. Every run
     * pinned to the version fails with {@code SECRET_VERSION_REVOKED} from the next resolution onward, and none
     * of them advances to another version.
     */
    public SecretVersion revokeVersion(
            TenantPrincipal principal, UUID projectId, UUID secretReferenceId, int version) {
        Instant now = clock.instant();
        SecretVersion revoked = transactions.execute(status -> {
            if (!versions.lockReference(principal.organizationId(), projectId, secretReferenceId)) {
                throw ApiException.notFound();
            }
            if (versions.find(principal.organizationId(), projectId, secretReferenceId, version).isEmpty()) {
                throw ApiException.notFound();
            }
            boolean newlyRevoked = versions.revoke(
                    principal.organizationId(), projectId, secretReferenceId, version, principal.principalId(), now);
            if (newlyRevoked) {
                LOGGER.atWarn()
                        .addKeyValue("event", "SECRET_VERSION_REVOKED")
                        .addKeyValue("organizationId", principal.organizationId())
                        .addKeyValue("projectId", projectId)
                        .addKeyValue("secretReferenceId", secretReferenceId)
                        .addKeyValue("version", version)
                        .log("Revoked a secret version and destroyed its ciphertext");
                count("kaas.secret.version.revoked", "REVOKED");
            }
            return versions.find(principal.organizationId(), projectId, secretReferenceId, version)
                    .orElseThrow(ApiException::notFound);
        });
        return revoked;
    }

    private static ApiException refusal(SecretFailure failure) {
        return switch (failure) {
            case SECRET_VALUE_INVALID -> ApiException.validation(
                    "/", "A secret value must be a non-empty UTF-8 byte sequence.");
            case SECRET_VALUE_TOO_LARGE -> ApiException.validation(
                    "/", "A secret value may be at most 8192 bytes.");
            // Every provider-side failure is the same answer to a tenant. Which one it was is in the metric and
            // in Vault's own audit log, where an operator will look; telling a caller whether Vault is sealed or
            // merely slow tells them something about the platform and nothing they can act on.
            default -> ApiException.unavailable(
                    "SECRET_PROVIDER_UNAVAILABLE", "Secrets cannot be stored right now. Retry later.");
        };
    }

    private void count(String name, String result) {
        Counter.builder(name).tag("result", result).register(meters).increment();
    }
}
