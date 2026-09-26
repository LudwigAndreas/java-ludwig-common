package ru.ludwigandreas.audit.store.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.Builder;

/**
 * What to look for in the trail.
 *
 * <p>A criteria object rather than a row of nullable parameters, because the whole point of the
 * consolidation is that an auditor asks combined questions - "everything this person did last Tuesday",
 * "every denied access to this resource this month" - and each combination as its own finder method is
 * how a read side becomes twenty methods nobody can tell apart.
 *
 * <p>Every field is optional and an absent one is not a filter. {@link #limit()} is not optional: an
 * unbounded read of an append-only table that has been accumulating for years is a query that takes the
 * service down rather than a query that returns a lot.
 *
 * @param actorSubject  who did it
 * @param onBehalfOf    who it was done for, for the administrator-acted-on-a-user question
 * @param categories    which subsystems, empty for all
 * @param actions       which actions, empty for all
 * @param resourceType  what kind of thing it was about
 * @param resourceId    which thing
 * @param outcomes      which outcomes, empty for all - the denied-only query is the common one
 * @param correlationId everything that happened in one unit of work
 * @param from          inclusive lower bound on {@code occurredAt}
 * @param to            exclusive upper bound on {@code occurredAt}
 * @param limit         the most rows to return; clamped to {@link #MAX_LIMIT}
 */
@Builder
public record AuditTrailQuery(
        String actorSubject,
        String onBehalfOf,
        Collection<String> categories,
        Collection<String> actions,
        String resourceType,
        String resourceId,
        Collection<String> outcomes,
        String correlationId,
        Instant from,
        Instant to,
        int limit) {

    /**
     * The ceiling on any single read.
     *
     * <p>A hard ceiling rather than a configurable one. A caller that genuinely needs the whole trail
     * wants an export, not a page of a hundred thousand rows assembled in the heap of a service that is
     * also serving requests, and a deployment that could raise this would eventually raise it during
     * the incident when the trail was most needed.
     */
    public static final int MAX_LIMIT = 1000;

    /** Normalises the criteria. */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor, assembled by the
    // generated builder; there is no positional call site.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AuditTrailQuery {
        categories = categories == null ? List.of() : List.copyOf(categories);
        actions = actions == null ? List.of() : List.copyOf(actions);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        limit = limit <= 0 ? MAX_LIMIT : Math.min(limit, MAX_LIMIT);
    }
}
