package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The email fan-out of one announcement.
 *
 * <h2>This service's own run table, and why that is the rule rather than a choice</h2>
 *
 * <p>The platform's long-running-operation contract is explicit: there is <b>no shared operation
 * table</b>, and {@code web-core} carries no persistence dependency. Each module keeps its own run
 * table and maps onto {@code OperationResponse} at its edge. This is that table.
 *
 * <h2>The status is the platform's vocabulary, not a copy of it</h2>
 *
 * <p>{@link #status} is {@link OperationStatus} itself. This service declares no enum restating
 * {@code PENDING RUNNING SUCCEEDED FAILED CANCELLED} - {@code RuleGroup.OPERATIONS} fails the build
 * on a restatement, and this change relies on that existing rule rather than adding a copy of it.
 * That rule exists because two such enums cost a Liquibase changeset the last time they diverged.
 *
 * <h2>Why a cursor and not an offset</h2>
 *
 * <p>{@link #cursorSubject} is a keyset position: the last subject id a committed batch covered. A
 * run interrupted by a lost lease, a restart or a cancellation resumes from there. An {@code OFFSET}
 * would get slower with every batch and - worse - would skip or repeat rows whenever the underlying
 * set shifted, which over a run lasting minutes it will.
 *
 * <p><b>Exactly-once is not this table's job.</b> It rests on {@code notification_delivery}'s
 * existing unique dedup key, so a resumed batch that re-processes a recipient simply cannot create a
 * second delivery for them. That is why there is no per-recipient progress table here: the overlap is
 * made harmless rather than avoided, which is a much smaller thing to get right.
 *
 * <h2>Why this is not an audited entity</h2>
 *
 * <p>{@link GeneratedEntity} rather than {@code AuditedEntity}, which the announcement itself does
 * use. "Who did this and when" is already recorded against the announcement - the run is machinery
 * that followed from somebody's publish, not a thing anybody authored - so audit columns here would
 * be a second, emptier copy of that answer, and {@code created_by} would be whichever replica
 * happened to insert the row.
 *
 * <p>The lifecycle instants are explicit columns instead, and each one means one thing: when it was
 * asked for, when it first did work, when it stopped. That is the same choice
 * {@code NotificationDeliveryEntity} makes, where an inherited {@code updated_at} would have reported
 * the last ORM write rather than the last change.
 *
 * <p>{@link #audienceTotal} is nullable on purpose. The operation envelope's progress has a nullable
 * total for exactly this case, and counting a hundred thousand rows before starting would delay the
 * first send to produce a number nobody is waiting for. Guessing one would be worse than admitting it
 * is not yet known.
 */
@Entity
@Table(name = "notification_announcement_email_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementEmailRunEntity extends GeneratedEntity<UUID> {

    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    /**
     * The platform's status vocabulary, stored as a string.
     *
     * <p>A string column rather than a database enum so that a status added to the platform's
     * vocabulary does not need a migration here - the contract owns those values, this service only
     * records which one a run is in.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OperationStatus status;

    /** The last subject id a committed batch covered; null before the first batch. */
    @Column(name = "cursor_subject", length = 255)
    private String cursorSubject;

    @Column(name = "deliveries_created", nullable = false)
    private long deliveriesCreated;

    /** Null until known; the envelope's progress total is nullable for this reason. */
    @Column(name = "audience_total")
    private Long audienceTotal;

    /**
     * Whether a stop has been <em>requested</em>.
     *
     * <p>Cancellation is cooperative: the endpoint sets this and answers 202, and the loop notices
     * between batches. A flag rather than an immediate status change, because the run may be mid-batch
     * on another replica and the status must not claim it has stopped before it has.
     */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * When this run last made progress.
     *
     * <p>Set explicitly rather than by an auditing listener, so it means "the last batch committed"
     * and not "the last time Hibernate happened to write this row". An operator looking at a run that
     * appears stuck needs the first.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
