package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.notification.service.DeliveryAdminService;
import ru.ludwigandreas.notification.service.model.DeliveryQuery;
import ru.ludwigandreas.notification.service.model.DeliveryView;
import ru.ludwigandreas.notification.web.dto.DeliveryContentResponse;
import ru.ludwigandreas.notification.web.dto.DeliveryResponse;
import ru.ludwigandreas.notification.web.dto.DeliveryTransitionResponse;
import ru.ludwigandreas.notification.web.mapper.NotificationDtoMapper;
import ru.ludwigandreas.webcore.web.PageResponse;

/**
 * Delivery history and the two operator actions that change it.
 *
 * <p>Authorization comes in two layers and only the first is visible here. The
 * {@code @PreAuthorize} annotations are the resource-level gate - may this caller use this endpoint
 * at all - and that is the whole of what the web layer decides. <em>Which</em> deliveries come back,
 * and whether this caller may touch <em>this</em> one, is data-level and lives where the rows are:
 * the scoped query in {@code DeliveryQueryRepositoryImpl} and the guard in
 * {@code DeliveryAdminService}. Putting row rules in a controller annotation is the usual way they
 * end up enforced on one endpoint and forgotten on the next.
 *
 * <p>The search endpoint takes the OData options as plain request parameters and passes them down
 * unparsed, to be turned into a predicate by the layer that owns the entity. The starter's
 * deprecated {@code ODataQuery<T>} argument resolver would bind them into a predicate here, but
 * only by naming the JPA entity in the controller signature - and an entity in a controller
 * signature is the thing the layering rules exist to prevent.
 */
@Tag(name = "Delivery administration", description = "Inspect delivery history, retry and cancel")
@RestController
@RequestMapping("/api/v1/notifications/deliveries")
@RequiredArgsConstructor
public class DeliveryAdminController {

    private final DeliveryAdminService adminService;
    private final NotificationDtoMapper mapper;

    /**
     * OData-style search, e.g.
     * {@code GET /api/v1/notifications/deliveries?$filter=status eq 'DEAD' and channel eq 'EMAIL'&$top=50}.
     *
     * <p>Which fields may appear in {@code $filter} is decided by the entity's {@code @Filterable}
     * annotations, not by this signature - which is what keeps the recipient address and the rendered
     * body unreachable from a query string however it is spelled.
     */
    @Operation(summary = "Search delivery history",
            description = "OData $filter/$orderby/$top/$skip over non-PII delivery columns.")
    @GetMapping
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public PageResponse<DeliveryResponse> search(
            @RequestParam(name = "$filter", required = false) String filter,
            @RequestParam(name = "$orderby", required = false) String orderBy,
            @RequestParam(name = "$top", required = false) Integer top,
            @RequestParam(name = "$skip", required = false) Integer skip) {
        Page<DeliveryView> page = adminService.search(new DeliveryQuery(filter, orderBy, top, skip));
        return PageResponse.of(page, mapper::toResponse);
    }

    @Operation(summary = "Read one delivery")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public DeliveryResponse get(@PathVariable UUID id) {
        return mapper.toResponse(adminService.get(id));
    }

    @Operation(summary = "Read a delivery's status trail",
            description = "Every transition, oldest first - how long it waited, how often it failed, "
                    + "and why it ended where it did.")
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public java.util.List<DeliveryTransitionResponse> history(@PathVariable UUID id) {
        return mapper.toTransitionResponses(adminService.history(id));
    }

    /**
     * Returns what was actually sent.
     *
     * <p>A separate endpoint with its own role, because it is the most sensitive read this service
     * offers - and every call is logged naming the operator. Answers 410 once the content retention
     * window has passed, which is a different fact from the delivery not existing.
     */
    @Operation(summary = "Read a delivery's rendered body",
            description = "Audited. Subject to the content retention window - 410 once it has passed.")
    @GetMapping("/{id}/content")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public DeliveryContentResponse content(@PathVariable UUID id) {
        return mapper.toResponse(adminService.content(id));
    }

    /**
     * Puts a dead delivery back in the queue.
     *
     * <p>Admin only, and only from {@code DEAD}: retrying a sent delivery would send it twice, and
     * retrying a suppressed one would override an opt-out by hand.
     */
    @Operation(summary = "Requeue a DEAD delivery",
            description = "Resets the attempt counter, because a retry follows a fix to the cause.")
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('NOTIFICATION_ADMIN')")
    public DeliveryResponse retry(@PathVariable UUID id) {
        return mapper.toResponse(adminService.retry(id));
    }

    /**
     * Stops a delivery that has not gone out.
     *
     * <p>{@code CLAIMED} deliberately cannot be cancelled - it may be inside a provider call right
     * now, and "cancelled" would be a claim this service cannot make good on.
     */
    @Operation(summary = "Cancel a delivery that has not been sent")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public DeliveryResponse cancel(@PathVariable UUID id) {
        return mapper.toResponse(adminService.cancel(id));
    }
}
