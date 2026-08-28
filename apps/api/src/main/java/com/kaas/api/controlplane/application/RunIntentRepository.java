package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunSnapshot;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import com.kaas.api.controlplane.domain.TestRun;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RunIntentRepository {
    List<SnapshotFeature> findFeatureRevisions(UUID organizationId, UUID projectId, Set<UUID> revisionIds);

    void insert(UUID organizationId, TestRun run, RunSnapshot snapshot);

    Optional<TestRun> findRun(UUID organizationId, UUID runId);

    PageResult<TestRun> listRuns(UUID organizationId, UUID projectId, int page, int size);

    Optional<RunSnapshot> findSnapshot(UUID organizationId, UUID runId);
}
