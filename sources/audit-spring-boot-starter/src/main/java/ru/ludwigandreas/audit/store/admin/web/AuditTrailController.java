package ru.ludwigandreas.audit.store.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.audit.store.admin.AuditAdminOperations;
import ru.ludwigandreas.audit.store.admin.service.AuditTrailSearch;
import ru.ludwigandreas.audit.store.admin.service.AuditTrailSearch.EventView;
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
     * One page of the stored trail, newest first.
     *
     * @param filter an OData {@code $filter} over the audit event's filterable fields; without one constraining
     *               {@code occurredAt}, the last 30 days
     * @param cursor the previous page's cursor
     * @param size   the page size, at most 200
     * @return one page
     */
    @GetMapping("/events")
    @AdminAccess(AuditAdminOperations.READ)
    public AdminPage<EventView> events(@RequestParam(name = "$filter", required = false) String filter,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Integer size) {
        return search.search(filter, cursor, size);
    }
}
