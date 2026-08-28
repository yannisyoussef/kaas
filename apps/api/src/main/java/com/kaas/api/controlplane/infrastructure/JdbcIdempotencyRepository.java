package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.IdempotencyRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyRepository implements IdempotencyRepository {
    private final JdbcTemplate jdbc;

    JdbcIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(Scope scope) {
        jdbc.query(
                "select pg_advisory_xact_lock(?)",
                preparedStatement -> preparedStatement.setLong(1, lockIdentity(scope)),
                resultSet -> null);
    }

    @Override
    public Optional<Record> find(Scope scope) {
        return jdbc.query(
                        """
                        select request_sha256, resource_id, http_status, location
                          from api_idempotency_keys
                         where organization_id = ? and principal_id = ? and operation = ?
                           and scope_path = ? and idempotency_key = ?
                        """,
                        (resultSet, rowNumber) -> new Record(
                                resultSet.getString("request_sha256").strip(),
                                resultSet.getObject("resource_id", UUID.class),
                                resultSet.getInt("http_status"),
                                resultSet.getString("location")),
                        scope.organizationId(),
                        scope.principalId(),
                        scope.operation(),
                        scope.scopePath(),
                        scope.key())
                .stream()
                .findFirst();
    }

    @Override
    public void insert(Scope scope, String requestSha256, UUID resourceId, int status, String location, Instant now) {
        jdbc.update(
                """
                insert into api_idempotency_keys
                    (organization_id, principal_id, operation, scope_path, idempotency_key,
                     request_sha256, resource_id, http_status, location, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                scope.organizationId(),
                scope.principalId(),
                scope.operation(),
                scope.scopePath(),
                scope.key(),
                requestSha256,
                resourceId,
                status,
                location,
                Timestamp.from(now));
    }

    private static long lockIdentity(Scope scope) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, scope.organizationId().toString());
            update(digest, scope.principalId());
            update(digest, scope.operation());
            update(digest, scope.scopePath());
            update(digest, scope.key());
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
