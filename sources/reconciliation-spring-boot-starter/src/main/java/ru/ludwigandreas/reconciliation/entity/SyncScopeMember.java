package ru.ludwigandreas.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * That an item is a member of a scope, and when a walk last saw it.
 *
 * <p>Deliberately narrow, and deliberately the engine's rather than a column on the integration's
 * table. Stamping "seen" on a domain row would churn its version, its {@code updated_at} and its audit
 * trail on every full walk; and the engine could not then say which members a walk missed without
 * knowing the integration's schema.
 *
 * <p>There is no {@code @Version} column on purpose. Rows are written by the one instance holding the
 * task's run lease, and "seen" is stamped with a bulk update that would bypass a version anyway.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_scope_member")
public class SyncScopeMember extends GeneratedEntity<UUID> {

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    @Column(name = "scope_key", nullable = false, updatable = false)
    private String scopeKey;

    @Column(name = "member_key", nullable = false, updatable = false)
    private String memberKey;

    /** The full-walk generation that last listed this member. */
    @Column(name = "seen_generation", nullable = false)
    private long seenGeneration;

    /**
     * Hash of the normalized payload last applied; null for a member that has never applied.
     *
     * <p>An item arriving with the same hash is not handed to the integration at all.
     */
    @Column(name = "payload_hash")
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ScopeMemberStatus status = ScopeMemberStatus.ACTIVE;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
