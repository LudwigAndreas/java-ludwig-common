package ru.ludwigandreas.audit.store.admin.web;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.audit.store.admin.AuditAdminOperations;
import ru.ludwigandreas.audit.store.admin.service.AuditTrailSearch;
import ru.ludwigandreas.audit.store.admin.service.AuditTrailSearch.EventView;
import ru.ludwigandreas.audit.store.admin.service.AuditTrailSearch.Filter;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminPage;

/**
 * The {@code audit} administrative area: search the stored trail.
 */
@RestController
@RequestMapping("/admin/v1/audit")
public class AuditTrailController {

    private final AuditTrailSearch search;

    /**
     * Creates the controller.
     *
     * @param search the trail search
     */
    public AuditTrailController(AuditTrailSearch search) {
        this.search = search;
    }

    /**
     * Matching events, newest first; the last 30 days when no range is given.
     *
     * @param category     the event category
     * @param action       the event action
     * @param actor        who acted
     * @param resourceType the kind of object
     * @param resourceId   the object
     * @param outcome      how it ended
     * @param from         from this instant, inclusive
     * @param to           until this instant, exclusive
     * @param cursor       the previous page's cursor
     * @param size         the page size, at most 200
     * @return one page
     */
    @GetMapping("/events")
    @AdminAccess(AuditAdminOperations.READ)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AdminPage<EventView> events(@RequestParam(required = false) String category,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) String actor,
                                       @RequestParam(required = false) String resourceType,
                                       @RequestParam(required = false) String resourceId,
                                       @RequestParam(required = false) String outcome,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Integer size) {
        return search.search(new Filter(category, action, actor, resourceType, resourceId, outcome, from, to),
                cursor, size);
    }
}
