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
import ru.ludwigandreas.audit.store.repository.AuditTrailQuery;
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
    private final AdminAudit audit;
    private final Clock clock;

    /**
     * Creates the search.
     *
     * @param events the stored trail
     * @param audit  the administrative audit
     * @param clock  what "the last 30 days" is measured against
     */
    public AuditTrailSearch(AuditEventQueryRepository events, AdminAudit audit, Clock clock) {
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * One page of matching events, newest first.
     *
     * @param filter what to match
     * @param cursor the previous page's cursor, or null
     * @param size   the requested page size, or null
     * @return the page
     */
    public AdminPage<EventView> search(Filter filter, String cursor, Integer size) {
        int pageSize = AdminCursor.pageSize(size);
        Instant afterOccurredAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = AdminCursor.decode(cursor, 2);
            afterOccurredAt = Instant.parse(position.get(0));
            afterId = UUID.fromString(position.get(1));
        }
        Instant from = filter.from() == null && filter.to() == null
                ? clock.instant().minus(DEFAULT_WINDOW) : filter.from();
        AuditTrailQuery query = AuditTrailQuery.builder()
                .categories(filter.category() == null ? List.of() : List.of(filter.category()))
                .actions(filter.action() == null ? List.of() : List.of(filter.action()))
                .actorSubject(filter.actorSubject())
                .resourceType(filter.resourceType())
                .resourceId(filter.resourceId())
                .outcomes(filter.outcome() == null ? List.of() : List.of(filter.outcome()))
                .from(from)
                .to(filter.to())
                .limit(pageSize + 1)
                .build();
        TenantScope scope = AdminInvocation.require().tenantScope();
        List<EventView> rows = events.findPage(query, scope.confined(), scope.tenant(), afterOccurredAt, afterId)
                .stream().map(EventView::of).toList();
        AdminPage<EventView> page = AdminPage.of(rows, pageSize,
                row -> AdminCursor.encode(row.occurredAt().toString(), row.id().toString()));
        audit.record(RESOURCE, null, AdminOutcome.SUCCEEDED, describe(filter, from, page.items().size()));
        return page;
    }

    private static Map<String, Object> describe(Filter filter, Instant from, int returned) {
        Map<String, Object> facts = new LinkedHashMap<>();
        put(facts, "category", filter.category());
        put(facts, "action", filter.action());
        put(facts, "actor", filter.actorSubject());
        put(facts, "resourceType", filter.resourceType());
        put(facts, "resourceId", filter.resourceId());
        put(facts, "outcome", filter.outcome());
        put(facts, "from", from == null ? null : from.toString());
        put(facts, "to", filter.to() == null ? null : filter.to().toString());
        facts.put("returned", returned);
        return facts;
    }

    private static void put(Map<String, Object> facts, String key, String value) {
        if (value != null) {
            facts.put(key, value);
        }
    }

    /**
     * What a search matches; every member is optional.
     *
     * @param category     the event category
     * @param action       the event action
     * @param actorSubject who acted
     * @param resourceType the kind of object
     * @param resourceId   the object
     * @param outcome      {@code SUCCESS}, {@code FAILURE}, {@code DENIED} or {@code PARTIAL}
     * @param from         from this instant, inclusive
     * @param to           until this instant, exclusive
     */
    public record Filter(String category, String action, String actorSubject, String resourceType,
                         String resourceId, String outcome, Instant from, Instant to) {
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
     * @param resourceType     the kind of object
     * @param resourceId       the object
     * @param outcome          how it ended
     * @param reason           why, when one was recorded
     * @param correlationId    the request it belonged to
     * @param attributes       the remaining facts, already redacted
     */
    public record EventView(UUID id, Instant occurredAt, String category, String action, String actorSubject,
                            String actorType, String actorDisplayName, String onBehalfOf, String resourceType,
                            String resourceId, String outcome, String reason, String correlationId,
                            Map<String, Object> attributes) {

        static EventView of(AuditEventEntity event) {
            return new EventView(event.getId(), event.getOccurredAt(), event.getCategory(), event.getAction(),
                    event.getActorSubject(), event.getActorType(), event.getActorDisplayName(), event.getOnBehalfOf(),
                    event.getResourceType(), event.getResourceId(), event.getOutcome(), event.getReason(),
                    event.getCorrelationId(), event.getAttributes());
        }
    }
}
