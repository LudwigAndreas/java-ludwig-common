package ru.ludwigandreas.outbox.admin.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.outbox.admin.OutboxAdminOperations;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminService;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.BulkRetryResult;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.Count;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.MessageDetail;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.MessageFilter;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.MessageView;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.security.admin.AdminPage;
import ru.ludwigandreas.security.operation.RequiresOperation;

/**
 * The {@code outbox} administrative area: browse by state, retry, bulk retry, discard.
 */
@RestController
@RequestMapping("/admin/v1/outbox")
public class OutboxAdminController {

    private final OutboxAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public OutboxAdminController(OutboxAdminService service) {
        this.service = service;
    }

    /**
     * Messages matching the filter, newest first, without payloads or errors.
     *
     * @param filter an OData {@code $filter} over the message's filterable fields
     * @param cursor the previous page's cursor
     * @param size   the page size, at most 200
     * @return one page
     */
    @GetMapping("/messages")
    @RequiresOperation(OutboxAdminOperations.MESSAGE_READ)
    public AdminPage<MessageView> messages(@RequestParam(name = "$filter", required = false) String filter,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer size) {
        return service.messages(filter, cursor, size);
    }

    /**
     * One message with its status history and last error.
     *
     * @param id the message id
     * @return the detail
     */
    @GetMapping("/messages/{id}")
    @RequiresOperation(OutboxAdminOperations.MESSAGE_READ)
    public MessageDetail message(@PathVariable UUID id) {
        return service.message(id);
    }

    /**
     * How many messages there are per state and destination.
     *
     * @return the counts
     */
    @GetMapping("/summary")
    @RequiresOperation(OutboxAdminOperations.MESSAGE_READ)
    public List<Count> summary() {
        return service.counts();
    }

    /**
     * Retries one failed or dead-lettered message.
     *
     * @param id the message id
     * @return the message, now {@code PENDING}
     */
    @PostMapping("/messages/{id}/retry")
    @RequiresOperation(OutboxAdminOperations.MESSAGE_RETRY)
    public MessageView retry(@PathVariable UUID id) {
        return service.retry(id);
    }

    /**
     * Retries at most 1000 of the oldest messages matching the filter.
     *
     * @param request the filter; its status is required
     * @return how many moved, and whether more match
     */
    @PostMapping("/messages/retry")
    @RequiresOperation(OutboxAdminOperations.MESSAGE_RETRY)
    public BulkRetryResult retryAll(@RequestBody BulkRetryRequest request) {
        return service.retryAll(new MessageFilter(request.status(), request.eventType(), request.destination(),
                request.createdBefore()));
    }

    /**
     * Discards a message so that it is never sent.
     *
     * @param id the message id
     * @return the message, now {@code DISCARDED}
     */
    @PostMapping("/messages/{id}/discard")
    @RequiresOperation(OutboxAdminOperations.MESSAGE_DISCARD)
    public MessageView discard(@PathVariable UUID id) {
        return service.discard(id);
    }

    /**
     * What a bulk retry matches.
     *
     * @param status        {@code FAILED} or {@code DEAD_LETTER}; required
     * @param eventType     the event type
     * @param destination   the destination
     * @param createdBefore only messages created before this instant
     */
    public record BulkRetryRequest(OutboxStatus status, String eventType, String destination, Instant createdBefore) {
    }
}
