package ru.ludwigandreas.audit.store.sink;

import java.time.Clock;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;
import ru.ludwigandreas.audit.store.metrics.AuditMetrics;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;

/**
 * Writes the trail to {@code audit_event}.
 *
 * <h2>The caller's transaction, deliberately</h2>
 *
 * <p>{@link Propagation#SUPPORTS}, so a write that happens inside a business transaction commits with
 * it and a write that happens outside one gets the repository's own. That is the property worth having,
 * and {@code SettingsAuditRecorder} already had it and said why: "an audit trail written in a separate
 * transaction is one that can record a change that was rolled back, or miss one that was not, and
 * either makes the whole trail something an auditor has to qualify rather than rely on."
 *
 * <p>Not {@code REQUIRES_NEW}, which is the tempting alternative and is wrong for the mutation
 * categories: a separate transaction survives the caller's rollback, so the trail would record changes
 * that never happened. Not {@code MANDATORY} either, because the observational categories genuinely have
 * no ambient transaction - an authorization denial happens before any business method is entered.
 *
 * <p>The consequence a reader should expect: for a mutation category this sink's failure marks the
 * caller's transaction rollback-only, which is exactly why
 * {@link ru.ludwigandreas.audit.AuditFailurePolicy#FAIL_OPERATION} is the right policy there - swallowing
 * it would leave the caller committing a transaction the driver has already doomed.
 */
public class JpaAuditSink implements AuditSink {

    private final AuditEventRepository repository;
    private final Clock clock;
    private final String sourceSystem;
    private final AuditMetrics metrics;

    /**
     * Creates the sink.
     *
     * @param repository   the trail
     * @param clock        supplies the row's write time; injected rather than {@code Instant.now()} so a
     *                     test can assert on it
     * @param sourceSystem which deployment is writing, recorded on every row
     * @param metrics      counts rows written, or {@code null} without a metrics stack
     */
    public JpaAuditSink(AuditEventRepository repository, Clock clock, String sourceSystem,
                        AuditMetrics metrics) {
        this.repository = repository;
        this.clock = clock;
        this.sourceSystem = sourceSystem;
        this.metrics = metrics;
    }

    /**
     * Writes the row, and counts it only once it is written.
     *
     * <p>After the save rather than before, which is the difference between a counter and a guess: a count
     * incremented on entry would keep rising while every write was failing, and
     * {@code ludwig.audit.events.written} is the figure somebody compares against the number of operations
     * the service performed. Under a mutation category the save can still be rolled back with the caller's
     * transaction afterwards, so the counter is "rows handed to the database", not "rows committed" - which
     * is why the failure counter, not this one, is the one to alert on.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void record(AuditEvent event) {
        repository.save(AuditEventEntity.of(event, clock.instant(), sourceSystem));
        if (metrics != null) {
            metrics.recorded(event);
        }
    }
}
