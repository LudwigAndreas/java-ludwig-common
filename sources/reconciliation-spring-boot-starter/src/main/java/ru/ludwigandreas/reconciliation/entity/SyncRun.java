package ru.ludwigandreas.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.ludwigandreas.db.core.entity.JpaBaseEntity;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;

/**
 * One reconciliation run that acquired its lease.
 *
 * <p>The id is the lease's run id - the value the run's audit events and staged rows already carry - so it is
 * assigned, not generated. There is no status column: a run is pending until {@link #startedAt}, running until
 * {@link #finishedAt}, and then whatever its {@link #outcome} says. The administrative edge maps that onto the
 * platform's operation status; the engine records facts and never a second status vocabulary.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_run")
public class SyncRun extends JpaBaseEntity<UUID> {

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    @Filterable
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, updatable = false)
    private DemandTier tier;

    @Filterable
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private RunKind kind;

    /** The subject that asked for it; null for a scheduled run. */
    @Filterable
    @Column(name = "triggered_by", updatable = false)
    private String triggeredBy;

    /** The lease owner that ran it. */
    @Filterable
    @Column(name = "instance")
    private String instance;

    @Filterable
    @Column(name = "correlation_id")
    private String correlationId;

    @Filterable
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Filterable
    @Column(name = "started_at")
    private Instant startedAt;

    @Filterable
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Filterable
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private RunOutcome outcome;

    @Filterable
    @Column(name = "failure_code")
    private String failureCode;

    /** Correlation keys the demand produced, after suppression. */
    @Column(name = "demanded")
    private Integer demanded;

    /** Records the partner returned. */
    @Column(name = "fetched")
    private Integer fetched;

    /** Rows staged, or applied under {@code mode: direct}. */
    @Column(name = "produced")
    private Integer produced;

    /** A dry run's report or a backfill's counts: keys and counts only, never a payload. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private String resultJson;
}
