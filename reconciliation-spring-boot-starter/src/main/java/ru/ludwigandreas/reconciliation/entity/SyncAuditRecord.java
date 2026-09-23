package ru.ludwigandreas.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * One audited event, written by {@code PersistingReconciliationAuditLogger}.
 *
 * <p>Deliberately one flat table for run boundaries, per-record transitions, job state changes, lease
 * events and operator actions, rather than five shaped tables. The question this table exists to
 * answer is always "what happened around this task, in order, on this day" - and answering it across
 * five tables means five queries and a manual merge, at the moment somebody is trying to work out why
 * a partner was called twice.
 *
 * <p>No foreign key to {@code sync_inbox_record} or {@code sync_remote_job}, for the same reason the
 * outbox's history table has none: an FK forces a key-share lock on the parent row on every insert,
 * contending with the claim that is already holding it. The audit trail also has to outlive the rows
 * it describes - a quarantined record that was eventually purged is exactly the case somebody asks
 * about later.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_audit_record")
public class SyncAuditRecord extends GeneratedEntity<UUID> {

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    /** What kind of thing happened: {@code RUN}, {@code RECORD}, {@code JOB}, {@code LEASE}, {@code OPERATOR}. */
    @Column(name = "category", nullable = false, updatable = false)
    private String category;

    /** The specific event, for example {@code run.started}, {@code record.quarantined}, {@code lease.reclaimed}. */
    @Column(name = "event", nullable = false, updatable = false)
    private String event;

    /** The subject, when there is one: a correlation key, a job id, a quota name. */
    @Column(name = "subject", updatable = false)
    private String subject;

    @Column(name = "from_state", updatable = false)
    private String fromState;

    @Column(name = "to_state", updatable = false)
    private String toState;

    @Column(name = "detail", updatable = false, columnDefinition = "text")
    private String detail;

    /** The operator who triggered it, for events that came from the actuator endpoint. */
    @Column(name = "principal", updatable = false)
    private String principal;

    @Column(name = "run_id", updatable = false)
    private UUID runId;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "instance", updatable = false)
    private String instance;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
