package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.Feature;
import com.kaas.api.controlplane.domain.FeatureRevision;
import com.kaas.api.controlplane.domain.FeatureRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.Project;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ControlPlaneRepository {
    void ensureOrganization(UUID organizationId, Instant now);

    Project insertProject(UUID organizationId, UUID projectId, String name, String principalId, Instant now);

    Optional<Project> findProject(UUID organizationId, UUID projectId);

    PageResult<Project> listProjects(UUID organizationId, int page, int size);

    FeatureRevision insertFeatureWithInitialRevision(
            UUID organizationId,
            UUID projectId,
            UUID featureId,
            UUID revisionId,
            String name,
            String logicalPath,
            String source,
            String sourceDigest,
            String principalId,
            Instant now);

    Optional<Feature> findFeature(UUID organizationId, UUID projectId, UUID featureId);

    PageResult<Feature> listFeatures(UUID organizationId, UUID projectId, int page, int size);

    FeatureRevision appendRevision(
            UUID organizationId,
            UUID projectId,
            UUID featureId,
            UUID revisionId,
            String source,
            String sourceDigest,
            String principalId,
            Instant now);

    Optional<FeatureRevision> findRevision(
            UUID organizationId, UUID projectId, UUID featureId, UUID revisionId);

    Optional<FeatureRevision> findRevisionByNumber(
            UUID organizationId, UUID projectId, UUID featureId, long revisionNumber);

    PageResult<FeatureRevisionSummary> listRevisions(
            UUID organizationId, UUID projectId, UUID featureId, int page, int size);
}
