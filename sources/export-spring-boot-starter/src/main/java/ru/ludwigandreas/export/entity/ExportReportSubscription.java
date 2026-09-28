package ru.ludwigandreas.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * A row in {@code export_report_subscription}: a saved report on a schedule.
 *
 * <h2>A missed window is skipped, never replayed</h2>
 *
 * <p>{@code last_run_at} records when the subscription last produced a run, and the scheduler uses it
 * to decide whether this tick is a new window - not to catch up on the ones it missed. An instance
 * that was down for a weekend comes back and produces one report, not forty-eight.
 *
 * <p>That is a deliberate loss. The alternative - replaying every missed window - turns a routine
 * outage into a storm against the database and every enrichment partner at once, on an instance that
 * has just started and is still warming its caches. A daily report that missed Saturday is a gap
 * somebody can fill by running it; a reporting tier that fell over on Monday morning is not.
 */
@Getter
@Setter
@Entity
@Table(name = "export_report_subscription")
@EntityListeners(AuditingEntityListener.class)
public class ExportReportSubscription extends GeneratedEntity<UUID> {

    @Column(name = "saved_report_id", nullable = false, updatable = false)
    private UUID savedReportId;

    /** Six-field Spring cron expression, seconds first, as {@code ScheduleSpec} takes it. */
    @Column(name = "cron", nullable = false, length = 128)
    private String cron;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone = "UTC";

    /** Who the produced file is for; each is sent a link, never an attachment. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipients", columnDefinition = "jsonb")
    private List<String> recipients;

    @Column(name = "format_id", nullable = false, length = 32)
    private String formatId;

    /** The subject the scheduled run executes as, whose authorities scope it like any other run. */
    @Column(name = "run_as", nullable = false, length = 255)
    private String runAs;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_run_id")
    private UUID lastRunId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;
}
