package com.kaas.api.controlplane.infrastructure;

import com.kaas.api.controlplane.application.ControlPlaneRepository;
import com.kaas.api.controlplane.domain.Feature;
import com.kaas.api.controlplane.domain.FeatureRevision;
import com.kaas.api.controlplane.domain.FeatureRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.Project;
import com.kaas.api.shared.ApiException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JpaControlPlaneRepository implements ControlPlaneRepository {
    private final ProjectJpaRepository projects;
    private final FeatureJpaRepository features;
    private final FeatureRevisionJpaRepository revisions;
    private final JdbcTemplate jdbc;

    JpaControlPlaneRepository(
            ProjectJpaRepository projects,
            FeatureJpaRepository features,
            FeatureRevisionJpaRepository revisions,
            JdbcTemplate jdbc) {
        this.projects = projects;
        this.features = features;
        this.revisions = revisions;
        this.jdbc = jdbc;
    }

    @Override
    public void ensureOrganization(UUID organizationId, Instant now) {
        jdbc.update(
                "insert into organizations (organization_id, created_at) values (?, ?) on conflict do nothing",
                organizationId,
                Timestamp.from(now));
    }

    @Override
    public Project insertProject(UUID organizationId, UUID projectId, String name, String principalId, Instant now) {
        try {
            return toDomain(projects.saveAndFlush(new ProjectEntity(projectId, organizationId, name, principalId, now)));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("PROJECT_NAME_CONFLICT", "A project with that name already exists.");
        }
    }

    @Override
    public Optional<Project> findProject(UUID organizationId, UUID projectId) {
        return projects.findByOrganizationIdAndProjectId(organizationId, projectId).map(JpaControlPlaneRepository::toDomain);
    }

    @Override
    public PageResult<Project> listProjects(UUID organizationId, int page, int size) {
        var result = projects.findByOrganizationId(
                organizationId, PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("projectId"))));
        return new PageResult<>(result.map(JpaControlPlaneRepository::toDomain).getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Override
    public FeatureRevision insertFeatureWithInitialRevision(
            UUID organizationId,
            UUID projectId,
            UUID featureId,
            UUID revisionId,
            String name,
            String logicalPath,
            String source,
            String sourceDigest,
            String principalId,
            Instant now) {
        try {
            features.saveAndFlush(new FeatureEntity(
                    featureId, organizationId, projectId, name, logicalPath, principalId, now));
            var entity = new FeatureRevisionEntity(
                    revisionId,
                    organizationId,
                    projectId,
                    featureId,
                    1,
                    source,
                    digestHex(sourceDigest),
                    principalId,
                    now);
            return toDomain(revisions.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("FEATURE_PATH_CONFLICT", "A feature with that logical path already exists.");
        }
    }

    @Override
    public Optional<Feature> findFeature(UUID organizationId, UUID projectId, UUID featureId) {
        return features.findByOrganizationIdAndProjectIdAndFeatureId(organizationId, projectId, featureId)
                .map(JpaControlPlaneRepository::toDomain);
    }

    @Override
    public PageResult<Feature> listFeatures(UUID organizationId, UUID projectId, int page, int size) {
        var result = features.findByOrganizationIdAndProjectId(
                organizationId,
                projectId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("featureId"))));
        return new PageResult<>(result.map(JpaControlPlaneRepository::toDomain).getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Override
    public FeatureRevision appendRevision(
            UUID organizationId,
            UUID projectId,
            UUID featureId,
            UUID revisionId,
            String source,
            String sourceDigest,
            String principalId,
            Instant now) {
        FeatureEntity feature = features.findTenantScopedForUpdate(organizationId, projectId, featureId)
                .orElseThrow(ApiException::notFound);
        long revisionNumber = feature.allocateRevisionNumber();
        return toDomain(revisions.saveAndFlush(new FeatureRevisionEntity(
                revisionId,
                organizationId,
                projectId,
                featureId,
                revisionNumber,
                source,
                digestHex(sourceDigest),
                principalId,
                now)));
    }

    @Override
    public Optional<FeatureRevision> findRevision(
            UUID organizationId, UUID projectId, UUID featureId, UUID revisionId) {
        return revisions.findByOrganizationIdAndProjectIdAndFeatureIdAndRevisionId(
                        organizationId, projectId, featureId, revisionId)
                .map(JpaControlPlaneRepository::toDomain);
    }

    @Override
    public Optional<FeatureRevision> findRevisionByNumber(
            UUID organizationId, UUID projectId, UUID featureId, long revisionNumber) {
        return revisions.findByOrganizationIdAndProjectIdAndFeatureIdAndRevisionNumber(
                        organizationId, projectId, featureId, revisionNumber)
                .map(JpaControlPlaneRepository::toDomain);
    }

    @Override
    public PageResult<FeatureRevisionSummary> listRevisions(
            UUID organizationId, UUID projectId, UUID featureId, int page, int size) {
        var result = revisions.findByOrganizationIdAndProjectIdAndFeatureId(
                organizationId,
                projectId,
                featureId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("revisionNumber"))));
        return new PageResult<>(result.map(JpaControlPlaneRepository::toSummary).getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    private static String digestHex(String sourceDigest) {
        return sourceDigest.substring("sha256:".length());
    }

    private static Project toDomain(ProjectEntity entity) {
        return new Project(
                entity.projectId,
                entity.name,
                entity.version,
                entity.createdBy,
                entity.createdAt,
                entity.updatedBy,
                entity.updatedAt);
    }

    private static Feature toDomain(FeatureEntity entity) {
        return new Feature(
                entity.featureId,
                entity.projectId,
                entity.name,
                entity.logicalPath,
                entity.createdBy,
                entity.createdAt);
    }

    private static FeatureRevision toDomain(FeatureRevisionEntity entity) {
        return new FeatureRevision(
                entity.revisionId,
                entity.featureId,
                entity.projectId,
                entity.revisionNumber,
                entity.source,
                "sha256:" + entity.sourceSha256,
                entity.createdBy,
                entity.createdAt);
    }

    private static FeatureRevisionSummary toSummary(FeatureRevisionEntity entity) {
        return new FeatureRevisionSummary(
                entity.revisionId,
                entity.featureId,
                entity.revisionNumber,
                "sha256:" + entity.sourceSha256,
                entity.createdBy,
                entity.createdAt);
    }
}
