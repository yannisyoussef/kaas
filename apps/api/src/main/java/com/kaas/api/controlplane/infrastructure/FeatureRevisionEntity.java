package com.kaas.api.controlplane.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "feature_revisions")
class FeatureRevisionEntity implements Persistable<UUID> {
    @Id
    @Column(name = "revision_id", nullable = false)
    UUID revisionId;

    @Column(name = "organization_id", nullable = false)
    UUID organizationId;

    @Column(name = "project_id", nullable = false)
    UUID projectId;

    @Column(name = "feature_id", nullable = false)
    UUID featureId;

    @Column(name = "revision_number", nullable = false)
    long revisionNumber;

    @Column(nullable = false, columnDefinition = "text")
    String source;

    @Column(name = "source_sha256", nullable = false, length = 64)
    String sourceSha256;

    @Column(name = "created_by", nullable = false, length = 255)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Transient
    private boolean newEntity = true;

    protected FeatureRevisionEntity() {}

    FeatureRevisionEntity(
            UUID revisionId,
            UUID organizationId,
            UUID projectId,
            UUID featureId,
            long revisionNumber,
            String source,
            String sourceSha256,
            String principalId,
            Instant now) {
        this.revisionId = revisionId;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.featureId = featureId;
        this.revisionNumber = revisionNumber;
        this.source = source;
        this.sourceSha256 = sourceSha256;
        this.createdBy = principalId;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return revisionId;
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
