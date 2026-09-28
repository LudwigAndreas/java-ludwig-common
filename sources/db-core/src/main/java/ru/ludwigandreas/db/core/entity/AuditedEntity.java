package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.Instant;

/**
 * The fully-loaded default for application-owned ("inner") entities: a database-generated id, full
 * created/updated-by-and-at auditing and optimistic locking. Most application entities should extend this
 * class directly.
 * <p>
 * Extends {@link GeneratedEntity} (rather than {@link UserAuditableEntity}) and re-declares the audit/version
 * fields itself instead: JPA has no portable way to add {@code @Id @GeneratedValue} on top of an {@code @Id}
 * field already declared without generation further up a {@code @MappedSuperclass} chain, so this class picks
 * the generated-id branch as its parent and duplicates the handful of audit fields instead.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditedEntity<ID extends Serializable> extends GeneratedEntity<ID> {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
}
