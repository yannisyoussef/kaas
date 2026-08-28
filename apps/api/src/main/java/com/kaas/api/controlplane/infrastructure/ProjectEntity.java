package com.kaas.api.controlplane.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "projects")
class ProjectEntity implements Persistable<UUID> {
    @Id
    @Column(name = "project_id", nullable = false)
    UUID projectId;

    @Column(name = "organization_id", nullable = false)
    UUID organizationId;

    @Column(nullable = false, length = 120)
    String name;

    @Version
    @Column(nullable = false)
    long version;

    @Column(name = "created_by", nullable = false, length = 255)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_by", nullable = false, length = 255)
    String updatedBy;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    protected ProjectEntity() {}

    ProjectEntity(UUID projectId, UUID organizationId, String name, String principalId, Instant now) {
        this.projectId = projectId;
        this.organizationId = organizationId;
        this.name = name;
        this.createdBy = principalId;
        this.createdAt = now;
        this.updatedBy = principalId;
        this.updatedAt = now;
    }

    @Override
    public UUID getId() {
        return projectId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newEntity = false;
    }
}
