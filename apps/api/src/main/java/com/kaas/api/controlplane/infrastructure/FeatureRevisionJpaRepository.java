package com.kaas.api.controlplane.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface FeatureRevisionJpaRepository extends JpaRepository<FeatureRevisionEntity, UUID> {
    Optional<FeatureRevisionEntity> findByOrganizationIdAndProjectIdAndFeatureIdAndRevisionId(
            UUID organizationId, UUID projectId, UUID featureId, UUID revisionId);

    Optional<FeatureRevisionEntity> findByOrganizationIdAndProjectIdAndFeatureIdAndRevisionNumber(
            UUID organizationId, UUID projectId, UUID featureId, long revisionNumber);

    Page<FeatureRevisionEntity> findByOrganizationIdAndProjectIdAndFeatureId(
            UUID organizationId, UUID projectId, UUID featureId, Pageable pageable);
}
