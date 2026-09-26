package ru.ludwigandreas.audit.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.db.core.entity.SnapshotEntity;

/**
 * One row of the platform's single audit trail.
 *
 * <p>Flat, and one table for every category. The reasoning is {@code SyncAuditRecord}'s, which had
 * already made the same call inside one module: the question this table exists to answer is "what
 * happened around this, in order, on this day", and answering it across several shaped tables means
 * several queries and a manual merge at the moment somebody is trying to work out why a partner was
 * called twice. This change extends that from five shapes in one module to nine across the platform.
 *
 * <h2>Append-only, and enforced</h2>
 *
 * <p>A {@link SnapshotEntity}, so {@code db-core}'s {@code SnapshotImmutabilityListener} rejects every
 * {@code @PreUpdate} - the platform's existing immutability mechanism rather than a second one. There
 * is no update path in this module and no delete path outside the retention purge.
 *
 * <p>{@code UserSettingAuditEntity} explicitly argued <em>against</em> this base class: "that family
 * models data imported from an outer system and carries the provenance columns to match, none of which
 * mean anything for a record this service authored itself." That was true of a table one service wrote
 * for itself, and it stops being true here, because this table receives events from more than one
 * writer. {@code sourceSystem} records which deployment wrote the row, and {@code importedAt} records
 * when it was written as opposed to when the thing happened - the two differ for an event relayed from
 * another service, and only {@code occurredAt} has meaning to an auditor. {@code sourceVersion} and
 * {@code sourceTimestamp} are genuinely unused here and stay null.
 *
 * <h2>No foreign keys</h2>
 *
 * <p>To anything, ever. Two reasons, both of which the outbox's and reconciliation's history tables
 * already gave: an FK forces a key-share lock on the parent row on every insert, contending with the
 * work being audited, and the trail has to outlive the rows it describes - a purged record that was
 * once quarantined is exactly the case somebody asks about later.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_event",
        indexes = {
                @Index(name = "idx_audit_event_actor", columnList = "actor_subject, occurred_at"),
                @Index(name = "idx_audit_event_category", columnList = "category, occurred_at"),
                @Index(name = "idx_audit_event_occurred", columnList = "occurred_at"),
                @Index(name = "idx_audit_event_resource", columnList = "resource_type, resource_id"),
                @Index(name = "idx_audit_event_correlation", columnList = "correlation_id")
        })
public class AuditEventEntity extends SnapshotEntity<UUID> {

    /** When the audited thing happened, which is never the same column as when the row was written. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    /**
     * The actor's stable id - the field that makes the trail joinable, and the same string
     * {@code db-core} stamps into {@code created_by} for the same actor. Never null: an event with no
     * attributable actor carries {@code system}.
     */
    @Column(name = "actor_subject", nullable = false, length = 255)
    private String actorSubject;

    @Column(name = "actor_type", length = 64)
    private String actorType;

    @Column(name = "actor_display_name", length = 255)
    private String actorDisplayName;

    /** The subject this was done for, when it differs from the actor. */
    @Column(name = "on_behalf_of", length = 255)
    private String onBehalfOf;

    @Column(name = "resource_type", length = 128)
    private String resourceType;

    /** Null for an event about a set rather than an object. */
    @Column(name = "resource_id", length = 255)
    private String resourceId;

    @Column(name = "resource_name", length = 512)
    private String resourceName;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** Why: a denial reason, an error message, which stages degraded. Never a payload. */
    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    /** What joins this row to the logs, traces and downstream calls of the same unit of work. */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    /**
     * Everything module-specific, as {@code jsonb}, already redacted before it reached this module.
     *
     * <p>{@code jsonb} and not a child table of name/value pairs. The attributes differ per action and
     * are read as a whole - "show me this event" - rather than filtered on; a child table would turn
     * every read into a join and every write into N inserts on the path of the work being audited.
     * It is deliberately not indexed: a GIN index on an append-only table this size costs write
     * throughput on every audited operation to speed up a query an auditor runs monthly, and a
     * deployment that genuinely needs it can add one without this module's participation.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    /**
     * The entity for one event.
     *
     * @param event        the envelope, attributes already redacted
     * @param writtenAt    when this row is being written, which the caller supplies from its clock
     * @param sourceSystem which deployment is writing it
     * @return the row, with the event's id as its primary key so a redelivery collides instead of
     *         appending a second copy of the same event
     */
    public static AuditEventEntity of(AuditEvent event, Instant writtenAt, String sourceSystem) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.setId(event.id());
        entity.setImportedAt(writtenAt);
        entity.setSourceSystem(sourceSystem);
        entity.setOccurredAt(event.occurredAt());
        entity.setCategory(event.category());
        entity.setAction(event.action());
        entity.setActorSubject(event.actor().subject());
        entity.setActorType(event.actor().principalType());
        entity.setActorDisplayName(event.actor().displayName());
        entity.setOnBehalfOf(event.actor().onBehalfOf());
        if (event.resource() != null) {
            entity.setResourceType(event.resource().type());
            entity.setResourceId(event.resource().id());
            entity.setResourceName(event.resource().name());
        }
        entity.setOutcome(event.outcome().status().name());
        entity.setReason(event.outcome().reason());
        entity.setCorrelationId(event.correlationId());
        entity.setTraceId(event.traceId());
        entity.setAttributes(event.attributes().isEmpty() ? null : Map.copyOf(event.attributes()));
        return entity;
    }
}
