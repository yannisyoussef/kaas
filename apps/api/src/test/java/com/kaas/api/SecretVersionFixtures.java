package com.kaas.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Version metadata for suites that exercise something OTHER than secrets.
 *
 * <p>Since KAAS-22 a run pins a version of every secret it binds, and a binding with no version cannot be
 * pinned, so a run cannot be created. Scheduling, snapshot and authorization suites that happen to bind a
 * secret therefore need a version to exist. They are not testing how one is written, so this inserts the
 * metadata and a well-formed Transit-shaped ciphertext directly.
 *
 * <p>The ciphertext is random bytes in the Transit envelope, not an encryption of anything: nothing in these
 * suites decrypts. The real write path — tenant plaintext through Vault Transit into this table — is proven in
 * {@code SecretVersionHttpIntegrationTests} and end to end in the secret-execution gate, against a real Vault.
 * This fixture is never used there.
 */
public final class SecretVersionFixtures {

    private SecretVersionFixtures() {}

    /** Inserts the next version of the reference and returns its number. */
    public static int seed(JdbcTemplate jdbc, UUID secretReferenceId) {
        Map<String, Object> owner = jdbc.queryForMap(
                "select organization_id, project_id from secret_references where secret_reference_id = ?",
                secretReferenceId);
        Integer latest = jdbc.queryForObject(
                "select coalesce(max(version_number), 0) from secret_versions where secret_reference_id = ?",
                Integer.class, secretReferenceId);
        int version = (latest == null ? 0 : latest) + 1;
        UUID versionId = UUID.randomUUID();
        jdbc.update(
                """
                insert into secret_versions
                    (secret_version_id, organization_id, project_id, secret_reference_id, version_number,
                     transit_key_name, transit_key_version, created_by, created_at)
                values (?, ?, ?, ?, ?, 'kaas-tenant-secrets', 1, 'fixture', ?)
                """,
                versionId, owner.get("organization_id"), owner.get("project_id"), secretReferenceId, version,
                Timestamp.from(Instant.now()));
        byte[] opaque = new byte[48];
        new java.security.SecureRandom().nextBytes(opaque);
        jdbc.update(
                """
                insert into secret_version_ciphertexts (secret_version_id, organization_id, project_id, ciphertext)
                values (?, ?, ?, ?)
                """,
                versionId, owner.get("organization_id"), owner.get("project_id"),
                "vault:v1:" + Base64.getEncoder().encodeToString(opaque));
        return version;
    }

    public static int seed(JdbcTemplate jdbc, String secretReferenceId) {
        return seed(jdbc, UUID.fromString(secretReferenceId));
    }
}
