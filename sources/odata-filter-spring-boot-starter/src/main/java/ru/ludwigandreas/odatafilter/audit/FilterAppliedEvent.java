package ru.ludwigandreas.odatafilter.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.odatafilter.ast.FilterSummary;

/**
 * A filter was parsed, validated and translated: which entity, which properties, which operators, and
 * which roles the caller held.
 *
 * <h2>This is not the audit mechanism, and it used to be</h2>
 *
 * <p>This record's javadoc used to say "write an {@code @EventListener} for this type to build an audit
 * trail of who queried what, e.g. for compliance logging in a multi-tenant system". That was the whole
 * of it, and it made this the one module in the reactor whose trail did not reach {@code audit-core}'s
 * single {@link AuditSink}: the trail existed only if every consuming service wrote the same forwarding
 * listener, so the deployment's {@code AuditFailurePolicy} governed nothing and nothing was ever
 * redacted. {@code ODataFilterService} now hands {@link #toAuditEvent()} to the sink itself.
 *
 * <p>The Spring application event is still published, and is still a reasonable thing to listen for -
 * an in-process hook, for a metric or a cache invalidation. What it is no longer is the way a filter
 * reaches the audit trail.
 *
 * <h2>What it carries, and what it deliberately does not</h2>
 *
 * <p>This record used to carry {@code rawFilter} (the caller's {@code $filter} verbatim) and
 * {@code resolvedPredicate} ({@code predicate.toString()}). Both are the caller's literal values:
 * {@code email eq 'a.sidorov@example.com'} reads the same either way. They are replaced by
 * {@link FilterSummary}, which records the paths and the operators and never reads a value - see its
 * javadoc for why a hash or a truncation would not have been good enough.
 *
 * @param entityType  the entity the filter was resolved against
 * @param summary     the property paths and operators the filter named; never a value
 * @param callerRoles the roles the caller held, as the active {@code FilterPrincipalResolver} reported
 *                    them
 * @param timestamp   when the filter was applied
 */
public record FilterAppliedEvent(
        Class<?> entityType,
        FilterSummary summary,
        Set<String> callerRoles,
        Instant timestamp) {

    public FilterAppliedEvent {
        summary = summary == null ? FilterSummary.empty() : summary;
        callerRoles = callerRoles == null ? Set.of() : Set.copyOf(callerRoles);
    }

    public FilterAppliedEvent(Class<?> entityType, FilterSummary summary, Set<String> callerRoles) {
        this(entityType, summary, callerRoles, Instant.now());
    }

    /**
     * Flattens into the platform's one audit envelope, as the other nine modules' event records do.
     *
     * <p>The resource is the entity's simple name rather than its fully-qualified one: a trail is read
     * years later by people who do not have the source tree, and a package name says nothing an auditor
     * can use. There is no resource id, because a filter is a question about a set and not about one row.
     *
     * <p>No attribute needs {@code Redaction.MASK}: the summary collects no value, so there is nothing
     * here that could need masking. That satisfies {@code audit-envelope}'s "attributes are redacted at
     * construction" by construction rather than by remembering to mask.
     *
     * @return the event in {@code audit-core}'s envelope, ready for the sink
     */
    public AuditEvent toAuditEvent() {
        return AuditEvent.builder()
                .occurredAt(timestamp)
                .category(AuditCategories.QUERY)
                .action("query.filtered")
                .resource(Resource.ofType(entityType.getSimpleName()))
                .attributes(Map.of(
                        "properties", summary.pathsAsText(),
                        "operators", summary.operatorsAsText(),
                        "callerRoles", new TreeSet<>(callerRoles).toString()))
                .build();
    }
}
