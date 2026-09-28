package ru.ludwigandreas.audit.store.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditFailurePolicy;
import ru.ludwigandreas.audit.AuditSinkFailureListener;

/**
 * Counters for the trail itself.
 *
 * <p>The failure counter is the one that matters, and it is the reason
 * {@link AuditSinkFailureListener} exists as a separate seam from the failure policy. A
 * {@link AuditFailurePolicy#LOG_AND_CONTINUE} sink that has been failing since a schema change is a
 * warning line in a log nobody reads; the first person to notice without a counter is an auditor asking
 * why a month is missing.
 *
 * <p>Tagged by category and policy rather than by exception class: "settings writes are failing" and
 * "everything is failing" are different incidents, and the exception class is in the log line the
 * listener's caller already wrote.
 */
public class AuditMetrics implements AuditSinkFailureListener {

    private static final String WRITTEN = "ludwig.audit.events.written";
    private static final String FAILED = "ludwig.audit.sink.failures";
    private static final String PURGED = "ludwig.audit.events.purged";

    private final MeterRegistry registry;

    /**
     * Binds the counters to {@code registry}.
     *
     * @param registry the meter registry
     */
    public AuditMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One event reached a persistent sink. */
    public void recorded(AuditEvent event) {
        registry.counter(WRITTEN, Tags.of("category", event.category(),
                "outcome", event.outcome().status().name())).increment();
    }

    /** The purge removed {@code rows} events from {@code category}. */
    public void purged(String category, long rows) {
        if (rows > 0) {
            registry.counter(PURGED, Tags.of("category", category == null ? "all" : category))
                    .increment(rows);
        }
    }

    @Override
    public void onFailure(AuditEvent event, AuditFailurePolicy policy, RuntimeException cause) {
        registry.counter(FAILED, Tags.of("category", event.category(), "policy", policy.name()))
                .increment();
    }
}
