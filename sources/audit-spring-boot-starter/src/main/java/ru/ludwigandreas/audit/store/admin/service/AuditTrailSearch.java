package ru.ludwigandreas.audit.store.admin.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;
import ru.ludwigandreas.audit.store.repository.AuditEventQueryRepository;
import ru.ludwigandreas.db.core.query.AdminQueryFilter;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminCursor;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.AdminPage;
import ru.ludwigandreas.security.admin.TenantScope;

/**
 * Searches the stored audit trail for the administrative surface.
 *
 * <p>Every search is itself recorded, with its filter and how many events it returned but none of them: reading
 * who did what is an act somebody may later need to see too. A person is confined to their own tenant's events; a
 * service principal is not - see {@link TenantScope}.
 */
public class AuditTrailSearch {

    /** How far back a search reaches when it names no time range. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private static final String RESOURCE = "audit-trail";

    private final AuditEventQueryRepository events;
    private final AdminQueryFilter filters;
    private final AdminAudit audit;
    private final Clock clock;

    /**
     * Creates the search.
     *
     * @param events  the stored trail
     * @param filters parses the {@code $filter}
     * @param audit   the administrative audit
     * @param clock   what "the last 30 days" is measured against
     */
    public AuditTrailSearch(AuditEventQueryRepository events, AdminQueryFilter filters, AdminAudit audit,
                            Clock clock) {
        this.events = events;
        this.filters = filters;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * One page of matching events, newest first.
     *
     * @param rawFilter an OData {@code $filter}, or null; without one constraining {@code occurredAt} the search
     *                  covers the last {@link #DEFAULT_WINDOW}
     * @param cursor    the previous page's cursor, or null
     * @param size      the requested page size, or null
     * @return the page
     */
    public AdminPage<EventView> search(String rawFilter, String cursor, Integer size) {
        int pageSize = AdminCursor.pageSize(size);
        Instant afterOccurredAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = AdminCursor.decode(cursor, 2);
            afterOccurredAt = Instant.parse(position.get(0));
            afterId = UUID.fromString(position.get(1));
        }
        AdminQueryFilter.ParsedFilter filter = filters.parse(AuditEventEntity.class, rawFilter);
        Instant from = filter.constrains("occurredAt") ? null : clock.instant().minus(DEFAULT_WINDOW);
        TenantScope scope = AdminInvocation.require().tenantScope();
        List<EventView> rows = events.findPage(filter.where().orElse(null), from, scope.confined(), scope.tenant(),
                afterOccurredAt, afterId, pageSize + 1).stream().map(EventView::of).toList();
        AdminPage<EventView> page = AdminPage.of(rows, pageSize,
                row -> AdminCursor.encode(row.occurredAt().toString(), row.id().toString()));
        Map<String, Object> facts = new LinkedHashMap<>(filter.auditFacts());
        if (from != null) {
            facts.put("from", from.toString());
        }
        facts.put("returned", page.items().size());
        audit.record(RESOURCE, null, AdminOutcome.SUCCEEDED, facts);
        return page;
    }

    /**
     * One stored event, as the envelope redacted it when it was recorded.
     *
     * @param id               the event id
     * @param occurredAt       when it happened
     * @param category         its category
     * @param action           what happened
     * @param actorSubject     who did it
     * @param actorType        what kind of principal
     * @param actorDisplayName a readable name, when one was known
     * @param onBehalfOf       for whom, when that differs
     * @param via              the party the actor acted through, e.g. an administrative UI
     * @param resourceType     the kind of object
     * @param resourceId       the object
     * @param outcome          how it ended
     * @param reason           why, when one was recorded
     * @param correlationId    the request it belonged to
     * @param attributes       the remaining facts, already redacted
     */
    public record EventView(UUID id, Instant occurredAt, String category, String action, String actorSubject,
                            String actorType, String actorDisplayName, String onBehalfOf, String via,
                            String resourceType, String resourceId, String outcome, String reason, String correlationId,
                            Map<String, Object> attributes) {

        static EventView of(AuditEventEntity event) {
            return new EventView(event.getId(), event.getOccurredAt(), event.getCategory(), event.getAction(),
                    event.getActorSubject(), event.getActorType(), event.getActorDisplayName(),
                    event.getOnBehalfOf(), event.getActorVia(), event.getResourceType(), event.getResourceId(),
                    event.getOutcome(), event.getReason(), event.getCorrelationId(), event.getAttributes());
        }
    }
}
