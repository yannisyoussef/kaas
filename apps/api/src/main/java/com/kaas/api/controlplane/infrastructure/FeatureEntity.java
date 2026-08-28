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
@Table(name = "features")
class FeatureEntity implements Persistable<UUID> {
    @Id
    @Column(name = "feature_id", nullable = false)
    UUID featureId;

    @Column(name = "organization_id", nullable = false)
    UUID organizationId;

    @Column(name = "project_id", nullable = false)
    UUID projectId;

    @Column(nullable = false, length = 160)
    String name;

    @Column(name = "logical_path", nullable = false, length = 512)
    String logicalPath;

    @Column(name = "next_revision_number", nullable = false)
    long nextRevisionNumber;

    @Version
    @Column(nullable = false)
    long version;

    @Column(name = "created_by", nullable = false, length = 255)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Transient
    private boolean newEntity = true;

    protected FeatureEntity() {}

    FeatureEntity(
            UUID featureId,
            UUID organizationId,
            UUID projectId,
            String name,
            String logicalPath,
            String principalId,
            Instant now) {
        this.featureId = featureId;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.name = name;
        this.logicalPath = logicalPath;
        this.nextRevisionNumber = 2;
        this.createdBy = principalId;
        this.createdAt = now;
    }

    long allocateRevisionNumber() {
        return nextRevisionNumber++;
    }

    @Override
    public UUID getId() {
        return featureId;
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
