package com.kaas.api.controlplane.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectJpaRepository extends JpaRepository<ProjectEntity, UUID> {
    Optional<ProjectEntity> findByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);

    Page<ProjectEntity> findByOrganizationId(UUID organizationId, Pageable pageable);
}
