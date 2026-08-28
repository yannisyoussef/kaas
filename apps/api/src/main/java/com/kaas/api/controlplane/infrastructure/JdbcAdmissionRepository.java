package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.AdmissionRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdmissionRepository implements AdmissionRepository {
    /**
     * Advisory lock class for admission. The two-argument form gives each lock class its own key space, so an
     * admission identity can never collide with an idempotency identity. A string prefix would not do this: it
     * only permutes bits inside the one shared space, and a collision there could invert the lock ordering that
     * keeps creation deadlock-free.
     */
    private static final int LOCK_CLASS = 2;

    private final JdbcTemplate jdbc;

    JdbcAdmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockOrganization(UUID organizationId) {
        jdbc.query(
                "select pg_advisory_xact_lock(?, ?)",
                preparedStatement -> {
                    preparedStatement.setInt(1, LOCK_CLASS);
                    preparedStatement.setInt(2, lockIdentity(organizationId));
                },
                resultSet -> null);
    }

    /**
     * Both counts are served by ix_test_runs_admission, so neither scans the full run history.
     *
     * <p>Active is every state that is not complete, rather than the two that exist today, so the ceiling keeps
     * its meaning as the lifecycle fills in instead of quietly ceasing to bind.
     */
    @Override
    public long countActiveRuns(UUID organizationId) {
        Long active = jdbc.queryForObject(
                "select count(*) from test_runs"
                        + " where organization_id = ? and lifecycle_state <> 'COMPLETED'",
                Long.class,
                organizationId);
        return active == null ? 0L : active;
    }

    @Override
    public long countQueuedRuns(UUID organizationId) {
        Long queued = jdbc.queryForObject(
                "select count(*) from test_runs where organization_id = ? and lifecycle_state = 'QUEUED'",
                Long.class,
                organizationId);
        return queued == null ? 0L : queued;
    }

    private static int lockIdentity(UUID organizationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, organizationId.toString());
            return ByteBuffer.wrap(digest.digest()).getInt();
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
