package com.kaas.api.secrets.infrastructure;

import com.kaas.api.secrets.application.SecretVersionRepository;
import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.SecretVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSecretVersionRepository implements SecretVersionRepository {
    private final JdbcTemplate jdbc;

    JdbcSecretVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean referenceExists(UUID organizationId, UUID projectId, UUID secretReferenceId) {
        Integer found = jdbc.queryForObject(
                """
                select count(*) from secret_references
                 where organization_id = ? and project_id = ? and secret_reference_id = ?
                """,
                Integer.class, organizationId, projectId, secretReferenceId);
        return found != null && found == 1;
    }

    @Override
    public boolean lockReference(UUID organizationId, UUID projectId, UUID secretReferenceId) {
        // FOR UPDATE takes the row lock without writing, so the immutability trigger on the reference does not
        // fire; it is the serialisation point for version numbering and revocation of this reference.
        return !jdbc.queryForList(
                        """
                        select secret_reference_id from secret_references
                         where organization_id = ? and project_id = ? and secret_reference_id = ?
                         for update
                        """,
                        UUID.class, organizationId, projectId, secretReferenceId)
                .isEmpty();
    }

    @Override
    public int nextVersionNumber(UUID secretReferenceId) {
        Integer latest = jdbc.queryForObject(
                "select coalesce(max(version_number), 0) from secret_versions where secret_reference_id = ?",
                Integer.class, secretReferenceId);
        return (latest == null ? 0 : latest) + 1;
    }

    @Override
    public SecretVersion insert(
            UUID organizationId,
            UUID projectId,
            UUID secretReferenceId,
            int version,
            SecretTransit.EncryptedValue encrypted,
            String createdBy,
            Instant createdAt) {
        UUID versionId = UUID.randomUUID();
        jdbc.update(
                """
                insert into secret_versions
                    (secret_version_id, organization_id, project_id, secret_reference_id, version_number,
                     transit_key_name, transit_key_version, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId, organizationId, projectId, secretReferenceId, version, encrypted.keyName(),
                encrypted.keyVersion(), createdBy, Timestamp.from(createdAt));
        jdbc.update(
                """
                insert into secret_version_ciphertexts (secret_version_id, organization_id, project_id, ciphertext)
                values (?, ?, ?, ?)
                """,
                versionId, organizationId, projectId, encrypted.ciphertext());
        return new SecretVersion(secretReferenceId, version, createdBy, createdAt, false, null);
    }

    @Override
    public List<SecretVersion> list(UUID organizationId, UUID projectId, UUID secretReferenceId) {
        return jdbc.query(
                SELECT + " where v.organization_id = ? and v.project_id = ? and v.secret_reference_id = ?"
                        + " order by v.version_number",
                JdbcSecretVersionRepository::version, organizationId, projectId, secretReferenceId);
    }

    @Override
    public Optional<SecretVersion> find(UUID organizationId, UUID projectId, UUID secretReferenceId, int version) {
        return jdbc.query(
                        SELECT + " where v.organization_id = ? and v.project_id = ? and v.secret_reference_id = ?"
                                + " and v.version_number = ?",
                        JdbcSecretVersionRepository::version,
                        organizationId, projectId, secretReferenceId, version)
                .stream()
                .findFirst();
    }

    @Override
    public boolean revoke(
            UUID organizationId, UUID projectId, UUID secretReferenceId, int version, String revokedBy, Instant at) {
        List<UUID> versionIds = jdbc.queryForList(
                """
                select secret_version_id from secret_versions
                 where organization_id = ? and project_id = ? and secret_reference_id = ? and version_number = ?
                """,
                UUID.class, organizationId, projectId, secretReferenceId, version);
        if (versionIds.isEmpty()) {
            return false;
        }
        UUID versionId = versionIds.getFirst();
        int recorded = jdbc.update(
                """
                insert into secret_version_revocations
                    (secret_version_id, organization_id, project_id, revoked_by, revoked_at)
                values (?, ?, ?, ?, ?)
                on conflict (secret_version_id) do nothing
                """,
                versionId, organizationId, projectId, revokedBy, Timestamp.from(at));
        // CRYPTO-SHREDDING, in the revocation's own transaction. After this there is nothing any key could
        // decrypt; the metadata row and the revocation record stay as the audit trail.
        jdbc.update("delete from secret_version_ciphertexts where secret_version_id = ?", versionId);
        return recorded == 1;
    }

    private static final String SELECT =
            """
            select v.secret_reference_id, v.version_number, v.created_by, v.created_at, r.revoked_at
              from secret_versions v
              left join secret_version_revocations r on r.secret_version_id = v.secret_version_id
            """;

    private static SecretVersion version(ResultSet row, int number) throws SQLException {
        Timestamp revokedAt = row.getTimestamp("revoked_at");
        return new SecretVersion(
                row.getObject("secret_reference_id", UUID.class),
                row.getInt("version_number"),
                row.getString("created_by"),
                row.getTimestamp("created_at").toInstant(),
                revokedAt != null,
                revokedAt == null ? null : revokedAt.toInstant());
    }
}
