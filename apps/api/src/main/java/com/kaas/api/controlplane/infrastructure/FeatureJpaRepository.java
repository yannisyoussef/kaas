package com.kaas.api.controlplane.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FeatureJpaRepository extends JpaRepository<FeatureEntity, UUID> {
    Optional<FeatureEntity> findByOrganizationIdAndProjectIdAndFeatureId(
            UUID organizationId, UUID projectId, UUID featureId);

    Page<FeatureEntity> findByOrganizationIdAndProjectId(UUID organizationId, UUID projectId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select f from FeatureEntity f
            where f.organizationId = :organizationId
              and f.projectId = :projectId
              and f.featureId = :featureId
            """)
    Optional<FeatureEntity> findTenantScopedForUpdate(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("featureId") UUID featureId);
}
