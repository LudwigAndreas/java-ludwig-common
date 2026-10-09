package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.notification.service.inbox.InboxService;
import ru.ludwigandreas.notification.web.dto.InboxItemResponse;
import ru.ludwigandreas.notification.web.dto.InboxSummaryResponse;
import ru.ludwigandreas.notification.web.mapper.InboxDtoMapper;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.webcore.web.PageResponse;

/**
 * The caller's own inbox. The only endpoints in this service a person talks to directly.
 *
 * <h2>There is no owner parameter, anywhere, and there must never be one</h2>
 *
 * <p>Every endpoint below answers for the authenticated caller and nobody else. There is no owner
 * path variable, no owner query parameter, and no owner field on any response that a client could
 * send back. The subject is resolved in {@link InboxService} from the security context.
 *
 * <p>That is the first of three independent defences, and they are independent on purpose:
 *
 * <ol>
 *   <li><b>No parameter to supply.</b> This class.</li>
 *   <li><b>No filter to spell.</b> {@code InboxItemEntity.ownerUserId} carries no
 *       {@code @Filterable}, so {@code $filter=ownerUserId eq '...'} is rejected as naming an
 *       unfilterable field - the same way a filter on a recipient address already is. An ArchUnit
 *       rule fails the build if the annotation is ever added.</li>
 *   <li><b>No status code to read.</b> A foreign item and a nonexistent item return the identical
 *       404. See below.</li>
 * </ol>
 *
 * <p>Any one of the three would be enough on a good day. Together they mean that removing one -
 * which somebody will eventually do, not knowing it was load-bearing - does not open the hole.
 *
 * <h2>Why a foreign item is 404 and not 403</h2>
 *
 * <p>403 is the conventional answer and it is the wrong one here, because a 403 is itself
 * information: it confirms that an item with that id exists. Repeated against guessed ids it
 * enumerates what the platform has told other people, and "has this person been told about X" is
 * precisely the fact the subject would consider private. So the two cases are indistinguishable -
 * same status, same body, same message - and {@code InboxScopingIT} asserts the two responses are
 * byte-identical rather than merely both being 404.
 *
 * <h2>Authorization is authentication</h2>
 *
 * <p>{@code isAuthenticated()} and no role. Reading one's own notifications is not a privilege
 * anybody grants: every authenticated person has an inbox, and gating it on a role would mean a
 * role that is either granted to everybody - and therefore means nothing - or forgotten for
 * somebody who then silently stops receiving notifications they can see. The row-level rule is the
 * owner predicate, and it lives where the rows are.
 *
 * <h2>POST rather than PATCH, and why these are not operations</h2>
 *
 * <p>The three transitions are named idempotent commands over a derived state machine rather than
 * partial edits of a document, which is what {@code POST} on a sub-resource says and what this
 * service already does for the operator actions on a delivery. None of them returns an
 * {@code OperationResponse}: each completes inside its request, so there is no status to poll,
 * nothing to cancel and no {@code Retry-After} to honour. The platform's operation contract is for
 * work that actually runs.
 *
 * <h2>The filterable surface is published, not described here</h2>
 *
 * <p>What may appear in {@code $filter} comes from the entity's {@code @Filterable} annotations and
 * is served as a document by the shared {@code FilterMetadataController} at
 * {@code /api/v1/filter-metadata/notification-inbox}. This controller deliberately does not restate
 * it: a list in this javadoc would be a second copy that goes stale the first time a field changes.
 */
@Tag(name = "Inbox", description = "The caller's own in-product notifications")
@RestController
@RequestMapping("/api/v1/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InboxService inboxService;
    private final InboxDtoMapper mapper;

    /**
     * The caller's inbox, newest first, excluding dismissed items.
     *
     * <p>Bodies are omitted from the page; fetch one item to read it. The page envelope is the
     * platform's, so the caller's absolute position is stated rather than implied.
     */
    @Operation(summary = "List the caller's notifications",
            description = "Newest first, dismissed items excluded. Supports "
                    + "$filter/$orderby/$top/$skip over the published surface. Bodies are omitted - "
                    + "fetch a single item to read one.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<InboxItemResponse> list(ODataQueryOptions options) {
        ODataPage<InboxItemResponse> page = inboxService.list(options).map(mapper::toResponse);
        return PageResponse.of(page.content(), page.offset(), page.size(), page.totalElements());
    }

    /**
     * How many notifications the caller has outstanding.
     *
     * <p>Its own endpoint rather than something a client derives from a page, because rendering a
     * badge needs the number and none of the content - and a client forced to page the inbox to
     * count it would fetch every row on every poll. It is also observable separately in the metrics,
     * so a client polling the wrong endpoint is visible as traffic rather than invisible.
     */
    @Operation(summary = "Count the caller's unread notifications",
            description = "Returns no content, for rendering an unread badge. Dismissed items are "
                    + "not counted as unread.")
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public InboxSummaryResponse unreadCount() {
        return mapper.toResponse(inboxService.summary());
    }

    /**
     * One of the caller's notifications, with its rendered body.
     *
     * <p>Includes dismissed items: dismissing is not deleting, and a client holding a link to
     * something it has just dismissed must still be able to open it.
     */
    @Operation(summary = "Read one notification",
            description = "Includes the rendered body. Answers 404 both when the item does not "
                    + "exist and when it belongs to somebody else - the two are deliberately "
                    + "indistinguishable, so that a status code cannot confirm another person's "
                    + "notification exists.")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public InboxItemResponse get(@PathVariable UUID id) {
        return mapper.toResponse(inboxService.get(id));
    }

    @Operation(summary = "Mark one notification seen",
            description = "Records that it was shown in a list, which is not the same as having been "
                    + "opened. Idempotent: a repeat leaves the original instant alone.")
    @PostMapping("/{id}/seen")
    @PreAuthorize("isAuthenticated()")
    public InboxItemResponse markSeen(@PathVariable UUID id) {
        return mapper.toResponse(inboxService.markSeen(id));
    }

    @Operation(summary = "Mark one notification read",
            description = "Also marks it seen if it was not already. Idempotent: a repeat leaves the "
                    + "original instant alone, which matters because that instant is what the "
                    + "retention window is measured from.")
    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public InboxItemResponse markRead(@PathVariable UUID id) {
        return mapper.toResponse(inboxService.markRead(id));
    }

    /**
     * Marks every one of the caller's unread notifications read.
     *
     * <p>Mapped on the collection rather than on an item, which is what makes it unambiguous that
     * the scope is "all of mine" - and there is no form of this endpoint that takes a scope.
     */
    @Operation(summary = "Mark every unread notification read",
            description = "Reports how many items moved. Safe to repeat: a second call reports zero "
                    + "rather than failing.")
    @PostMapping("/read")
    @PreAuthorize("isAuthenticated()")
    public InboxSummaryResponse markAllRead() {
        return mapper.toResponse(inboxService.markAllRead());
    }

    @Operation(summary = "Dismiss one notification",
            description = "Removes it from the default list without marking it read - the recipient "
                    + "dealt with it rather than opening it. Still retrievable by id. Idempotent.")
    @PostMapping("/{id}/dismiss")
    @PreAuthorize("isAuthenticated()")
    public InboxItemResponse dismiss(@PathVariable UUID id) {
        return mapper.toResponse(inboxService.dismiss(id));
    }
}
