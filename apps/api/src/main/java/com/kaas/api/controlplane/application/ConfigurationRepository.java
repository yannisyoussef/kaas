package com.kaas.api.controlplane.application;

import com.kaas.api.controlplane.domain.ConfigurationPolicy.EnvironmentContent;
import com.kaas.api.controlplane.domain.ConfigurationPolicy.RunProfileContent;
import com.kaas.api.controlplane.domain.Environment;
import com.kaas.api.controlplane.domain.EnvironmentRevision;
import com.kaas.api.controlplane.domain.EnvironmentRevisionSummary;
import com.kaas.api.controlplane.domain.PageResult;
import com.kaas.api.controlplane.domain.RunProfile;
import com.kaas.api.controlplane.domain.RunProfileRevision;
import com.kaas.api.controlplane.domain.RunProfileRevisionSummary;
import com.kaas.api.controlplane.domain.SecretReference;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ConfigurationRepository {
    boolean projectExists(UUID organizationId, UUID projectId);

    SecretReference insertSecretReference(
            UUID organizationId, UUID projectId, UUID referenceId, String name, String principalId, Instant now);

    Optional<SecretReference> findSecretReference(UUID organizationId, UUID projectId, UUID referenceId);

    PageResult<SecretReference> listSecretReferences(UUID organizationId, UUID projectId, int page, int size);

    boolean allSecretReferencesExist(UUID organizationId, UUID projectId, Set<UUID> referenceIds);

    EnvironmentRevision insertEnvironmentWithInitialRevision(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            UUID revisionId,
            String name,
            EnvironmentContent content,
            String principalId,
            Instant now);

    Optional<Environment> findEnvironment(UUID organizationId, UUID projectId, UUID environmentId);

    PageResult<Environment> listEnvironments(UUID organizationId, UUID projectId, int page, int size);

    EnvironmentRevision appendEnvironmentRevision(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            UUID revisionId,
            EnvironmentContent content,
            String principalId,
            Instant now);

    Optional<EnvironmentRevision> findEnvironmentRevision(
            UUID organizationId, UUID projectId, UUID environmentId, UUID revisionId);

    Optional<EnvironmentRevision> findEnvironmentRevisionById(
            UUID organizationId, UUID projectId, UUID revisionId);

    Optional<EnvironmentRevision> findEnvironmentRevisionByNumber(
            UUID organizationId, UUID projectId, UUID environmentId, long revisionNumber);

    PageResult<EnvironmentRevisionSummary> listEnvironmentRevisions(
            UUID organizationId, UUID projectId, UUID environmentId, int page, int size);

    RunProfileRevision insertRunProfileWithInitialRevision(
            UUID organizationId,
            UUID projectId,
            UUID runProfileId,
            UUID revisionId,
            String name,
            RunProfileContent content,
            String principalId,
            Instant now);

    Optional<RunProfile> findRunProfile(UUID organizationId, UUID projectId, UUID runProfileId);

    PageResult<RunProfile> listRunProfiles(UUID organizationId, UUID projectId, int page, int size);

    RunProfileRevision appendRunProfileRevision(
            UUID organizationId,
            UUID projectId,
            UUID runProfileId,
            UUID revisionId,
            RunProfileContent content,
            String principalId,
            Instant now);

    Optional<RunProfileRevision> findRunProfileRevision(
            UUID organizationId, UUID projectId, UUID runProfileId, UUID revisionId);

    Optional<RunProfileRevision> findRunProfileRevisionById(
            UUID organizationId, UUID projectId, UUID revisionId);

    Optional<RunProfileRevision> findRunProfileRevisionByNumber(
            UUID organizationId, UUID projectId, UUID runProfileId, long revisionNumber);

    PageResult<RunProfileRevisionSummary> listRunProfileRevisions(
            UUID organizationId, UUID projectId, UUID runProfileId, int page, int size);
}
