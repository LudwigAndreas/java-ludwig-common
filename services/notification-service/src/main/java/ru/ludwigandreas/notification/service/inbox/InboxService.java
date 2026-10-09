package ru.ludwigandreas.notification.service.inbox;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.InboxItemContentEntity;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.service.exception.InboxItemNotFoundException;
import ru.ludwigandreas.notification.service.mapper.InboxItemMapper;
import ru.ludwigandreas.notification.service.model.InboxItemView;
import ru.ludwigandreas.notification.service.model.InboxSummary;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * Everything a recipient can do with their own inbox.
 *
 * <h2>The owner is resolved here, from the caller, and is never a parameter</h2>
 *
 * <p>Every method below starts by asking {@link SecurityPrincipals#require()} who is calling. No
 * method takes an owner, and none must ever be given one: an owner parameter is the single change
 * that would turn this class into a way to read anybody's inbox, and it would look entirely
 * reasonable in a diff. {@code require()} rather than {@code current()} because business code that
 * cannot be correct without a caller must fail loudly rather than fall back to a default - a silent
 * fallback here is an unscoped query.
 *
 * <p>That is the first of three independent defences. The second is that
 * {@code InboxItemEntity.ownerUserId} carries no {@code @Filterable}, so no {@code $filter} can
 * reach it. The third is that a foreign item and a nonexistent one produce the same
 * {@link InboxItemNotFoundException}, so a 404 cannot be used to confirm that an item exists. Each
 * is enforced separately - by this class, by an ArchUnit rule, and by
 * {@code InboxScopingIT} respectively - because any one of them can be removed by somebody who did
 * not know the other two were load-bearing.
 *
 * <h2>The three instants are monotonic</h2>
 *
 * <p>{@code seenAt}, {@code readAt} and {@code dismissedAt} are each set once and never reset, and
 * every transition is idempotent: repeating one succeeds and leaves the original instant alone. They
 * are a record of what happened rather than a current state, which is what makes them safe to retry
 * over an unreliable connection - a client that double-taps "read" must not rewrite history, and a
 * client that could clear a flag could make an unread badge disagree with the list under it.
 *
 * <p>Marking an item read also marks it seen if it was not already, because having read something
 * one was never shown is not a state worth being able to represent.
 *
 * <h2>Why {@code Instant.now()} and not an injected {@code Clock}</h2>
 *
 * <p>The platform's pattern for a testable clock is a module-scoped bean - {@code fileIngestClock},
 * {@code ludwigRestClientClock} - and this service deliberately has none: every timestamp in it,
 * including the fan-out's and the dispatcher's, comes from {@code Instant.now()}. Introducing an
 * unqualified {@code Clock} bean here would also be a by-type injection hazard, because any starter
 * that later arrives on the classpath with its own {@code Clock} makes the injection ambiguous and
 * breaks startup several modules away from the change that caused it.
 *
 * <p>The tests do not need one. Every assertion about these instants is about ordering, nullness or
 * a value being <em>unchanged</em> across a repeated call, and none of those needs a fixed clock -
 * which is the usual reason to want one.
 *
 * <h2>Not a long-running operation</h2>
 *
 * <p>Nothing here returns an {@code OperationResponse}. Every method completes inside its request,
 * so there is no status to poll, nothing to cancel and no {@code Retry-After} to honour. The
 * platform's operation contract is for work that actually runs; wrapping a single-row update in it
 * would make a client poll for something already finished.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxService {

    private final InboxItemRepository items;
    private final InboxItemContentRepository contents;
    private final InboxItemMapper mapper;

    /**
     * One page of the caller's own inbox, newest first, excluding dismissed items.
     *
     * <p>Read-only and content-free: the page carries item rows without bodies, which is the whole
     * reason content lives in its own table. A list of fifty notifications must not be fifty
     * multi-kilobyte bodies the client then throws away.
     */
    @Transactional(readOnly = true)
    public ODataPage<InboxItemView> list(ODataQueryOptions options) {
        return items.search(currentOwner(), options).map(mapper::toViewWithoutContent);
    }

    /** One of the caller's items, with its rendered content. */
    @Transactional(readOnly = true)
    public InboxItemView get(UUID itemId) {
        InboxItemEntity item = requireOwned(itemId);
        return mapper.toView(item, contentOf(item));
    }

    /**
     * How many of the caller's items are outstanding.
     *
     * <p>Its own operation rather than something a client derives from a page, because the common
     * case - rendering a badge - needs the number and none of the content, and a client forced to
     * page the inbox to count it would fetch every row on every poll. Deliberately uncached: a count
     * over one indexed owner is cheap, and a cache here would need a {@code CachePurpose} nobody can
     * yet justify.
     */
    @Transactional(readOnly = true)
    public InboxSummary summary() {
        return new InboxSummary(items.countUnread(currentOwner()));
    }

    /** Records that the caller was shown this item in a list, as distinct from having opened it. */
    @Transactional
    public InboxItemView markSeen(UUID itemId) {
        InboxItemEntity item = requireOwned(itemId);
        if (item.getSeenAt() == null) {
            item.setSeenAt(Instant.now());
        }
        return mapper.toViewWithoutContent(items.save(item));
    }

    /**
     * Marks the item read, and seen if it was not already.
     *
     * <p>Idempotent by inspection rather than by an update predicate: the instants are only written
     * when unset, so a repeat is a no-op that still returns the current state. A client retrying
     * after a timeout gets the same answer as the call that actually succeeded.
     */
    @Transactional
    public InboxItemView markRead(UUID itemId) {
        InboxItemEntity item = requireOwned(itemId);
        Instant now = Instant.now();
        if (item.getReadAt() == null) {
            item.setReadAt(now);
        }
        if (item.getSeenAt() == null) {
            item.setSeenAt(now);
        }
        return mapper.toViewWithoutContent(items.save(item));
    }

    /**
     * Dismisses the item: gone from the default list, still fetchable by id.
     *
     * <p>Dismissing is not deleting, and the difference matters in both directions. A client holding
     * a link to an item it has just dismissed must still be able to open it, so
     * {@code findOwned} includes dismissed items. And a dismissed item is not read - the recipient
     * swiped it away rather than opening it - so {@code readAt} is deliberately left alone, while
     * the unread count treats dismissal as having dealt with it so that a badge can be cleared.
     */
    @Transactional
    public InboxItemView dismiss(UUID itemId) {
        InboxItemEntity item = requireOwned(itemId);
        if (item.getDismissedAt() == null) {
            item.setDismissedAt(Instant.now());
        }
        return mapper.toViewWithoutContent(items.save(item));
    }

    /**
     * Marks every one of the caller's unread items read, in one statement.
     *
     * <p>A QueryDSL update clause rather than a loop over loaded entities, and rather than native
     * SQL. A loop would be N round trips and would also be wrong under concurrency - an item
     * arriving between the read and the write would be counted and not updated. Native SQL would be
     * a third fenced package in a repository that has exactly two, each for a statement QueryDSL
     * genuinely cannot express; a conditional bulk update is not one of those.
     *
     * @return how many items moved - zero on a repeat, which is a success and not an error
     */
    @Transactional
    public InboxSummary markAllRead() {
        String owner = currentOwner();
        long moved = items.markAllRead(owner, Instant.now());
        log.debug("Marked {} inbox item(s) read for the current caller", moved);
        return new InboxSummary(moved);
    }

    /**
     * The item, or the same 404 the caller would get for an id that does not exist.
     *
     * <p>The owner is part of the query rather than compared afterwards, so there is no window in
     * which a foreign entity is loaded and available to be used by mistake.
     */
    private InboxItemEntity requireOwned(UUID itemId) {
        return items.findOwned(currentOwner(), itemId)
                .orElseThrow(() -> new InboxItemNotFoundException(itemId));
    }

    private String currentOwner() {
        return SecurityPrincipals.require().subject();
    }

    /**
     * Content is loaded for a single item and never for a page.
     *
     * <p>Kept as its own method so the asymmetry is visible: {@link #get} pays for the second read
     * because its caller is about to display the body, and {@link #list} does not because its caller
     * is about to display a list of subjects.
     */
    private InboxItemContentEntity contentOf(InboxItemEntity item) {
        return contents.findById(item.getId()).orElse(null);
    }
}
