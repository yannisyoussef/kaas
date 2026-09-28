package com.kaas.api.execution.infrastructure;

import com.kaas.api.execution.application.SubmittedAttestationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSubmittedAttestationRepository implements SubmittedAttestationRepository {
    private final JdbcTemplate jdbc;

    JdbcSubmittedAttestationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Stored> latestFor(String workerId) {
        return jdbc
                .query(
                        """
                        select worker_id, attestation_id, key_id, payload_digest, runtime_subject,
                               runtime_implementation_digest, assessed_at, received_at, document
                          from sandbox_attestations
                         where worker_id = ?
                         order by assessed_at desc, received_at desc
                         limit 1
                        """,
                        (resultSet, rowNumber) -> new Stored(
                                resultSet.getString("worker_id"),
                                resultSet.getString("attestation_id"),
                                resultSet.getString("key_id"),
                                resultSet.getString("payload_digest"),
                                resultSet.getString("runtime_subject"),
                                resultSet.getString("runtime_implementation_digest"),
                                resultSet.getTimestamp("assessed_at").toInstant(),
                                resultSet.getTimestamp("received_at").toInstant(),
                                resultSet.getString("document")),
                        workerId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean insert(Stored submission) {
        return jdbc.update(
                        """
                        insert into sandbox_attestations
                            (submission_id, worker_id, attestation_id, key_id, payload_digest, runtime_subject,
                             runtime_implementation_digest, assessed_at, received_at, document)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (worker_id, attestation_id) do nothing
                        """,
                        UUID.randomUUID(),
                        submission.workerId(),
                        submission.attestationId(),
                        submission.keyId(),
                        submission.payloadDigest(),
                        submission.runtimeSubject(),
                        submission.runtimeImplementationDigest(),
                        Timestamp.from(submission.assessedAt()),
                        Timestamp.from(submission.receivedAt()),
                        submission.document())
                == 1;
    }

    @Override
    public Instant currentDatabaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public int workersAssessedSince(Instant since) {
        Integer count = jdbc.queryForObject(
                "select count(distinct worker_id) from sandbox_attestations where assessed_at >= ?",
                Integer.class,
                Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override
    public int workersSeenWithEvidence(Instant seenSince, Instant assessedSince) {
        Integer count = jdbc.queryForObject(
                """
                select count(*) from worker_presence p
                 where p.last_seen_at >= ?
                   and exists (select 1 from sandbox_attestations s
                                where s.worker_id = p.worker_id and s.assessed_at >= ?)
                """,
                Integer.class,
                Timestamp.from(seenSince),
                Timestamp.from(assessedSince));
        return count == null ? 0 : count;
    }

    @Override
    public int workersSeenSince(Instant since) {
        Integer count = jdbc.queryForObject(
                "select count(*) from worker_presence where last_seen_at >= ?", Integer.class, Timestamp.from(since));
        return count == null ? 0 : count;
    }
}
