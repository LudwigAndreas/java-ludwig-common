package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;

/**
 * The bulk update and the unread count, against a real PostgreSQL.
 *
 * <p>These assertions cannot be made against a mocked repository, which is the reason this exists
 * alongside {@code InboxServiceTest}. Three of them in particular:
 *
 * <ul>
 *   <li><b>Cross-owner isolation.</b> A bulk {@code UPDATE} whose owner predicate was dropped would
 *       mark the whole table read, and every unit test built on one owner's data would still pass.
 *       This is the test that fails.</li>
 *   <li><b>The {@code COALESCE} on {@code seenAt}.</b> Expressed in QueryDSL and executed by
 *       Postgres, not by Java - so whether it preserves an existing instant or overwrites it is a
 *       property of the generated statement.</li>
 *   <li><b>Dismissed items are not unread.</b> The count and the update have to agree about which
 *       items they mean, or a badge cannot be cleared.</li>
 * </ul>
 */
class MarkAllReadIT extends NotificationTestBase {

    private static final String OWNER = "owner-1";
    private static final String OTHER_OWNER = "owner-2";

    @Autowired
    private InboxItemRepository items;

    @Autowired
    private InboxItemContentRepository contents;

    /**
     * The bulk update is a JPA bulk operation and needs an ambient transaction; in production
     * {@code InboxService.markAllRead} is {@code @Transactional} and supplies one.
     *
     * <p>Used here rather than annotating this class {@code @Transactional}, deliberately. A
     * transactional test would run the update and every assertion in one transaction that is then
     * rolled back, so a statement that only appeared to work before commit would still pass. This
     * way the update commits and the assertions read it back exactly as another request would.
     */
    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void resetState() {
        contents.deleteAll();
        items.deleteAll();
    }

    @Test
    @DisplayName("twelve unread items move, then a repeat moves none")
    void twelveThenZero() {
        for (int i = 0; i < 12; i++) {
            items.save(unread(OWNER));
        }

        assertThat(markAllRead(OWNER, Instant.now())).isEqualTo(12L);
        assertThat(items.countUnread(OWNER)).isZero();
        // The repeat is a success reporting nothing to do, not an error.
        assertThat(markAllRead(OWNER, Instant.now())).isZero();
    }

    /**
     * The assertion the whole class is for. If the owner predicate ever leaves the update clause,
     * this is what catches it.
     */
    @Test
    @DisplayName("another owner's items are untouched")
    void otherOwnersAreUntouched() {
        items.save(unread(OWNER));
        InboxItemEntity theirs = items.save(unread(OTHER_OWNER));

        assertThat(markAllRead(OWNER, Instant.now())).isEqualTo(1L);

        assertThat(items.findById(theirs.getId()).orElseThrow().getReadAt()).isNull();
        assertThat(items.countUnread(OTHER_OWNER)).isEqualTo(1L);
    }

    @Test
    @DisplayName("an already-seen item keeps its seen instant when it is marked read in bulk")
    void bulkReadPreservesTheSeenInstant() {
        Instant seenEarlier = Instant.parse("2026-01-04T09:00:00Z");
        InboxItemEntity item = unread(OWNER);
        item.setSeenAt(seenEarlier);
        UUID id = items.save(item).getId();

        markAllRead(OWNER, Instant.parse("2026-02-01T10:00:00Z"));

        InboxItemEntity reloaded = items.findById(id).orElseThrow();
        assertThat(reloaded.getSeenAt()).isEqualTo(seenEarlier);
        assertThat(reloaded.getReadAt()).isEqualTo(Instant.parse("2026-02-01T10:00:00Z"));
    }

    @Test
    @DisplayName("an unseen item is marked seen at the same instant it is marked read")
    void bulkReadFillsAnEmptySeenInstant() {
        UUID id = items.save(unread(OWNER)).getId();
        Instant at = Instant.parse("2026-02-01T10:00:00Z");

        markAllRead(OWNER, at);

        InboxItemEntity reloaded = items.findById(id).orElseThrow();
        assertThat(reloaded.getSeenAt()).isEqualTo(at);
        assertThat(reloaded.getReadAt()).isEqualTo(at);
    }

    /**
     * A recipient who swiped a notification away has dealt with it. Counting it as outstanding would
     * leave a badge nobody can clear, and including it in the bulk update would claim they read it.
     */
    @Test
    @DisplayName("a dismissed item is neither counted as unread nor marked read")
    void dismissedItemsAreNotUnread() {
        InboxItemEntity dismissed = unread(OWNER);
        dismissed.setDismissedAt(Instant.parse("2026-01-05T09:00:00Z"));
        UUID id = items.save(dismissed).getId();

        assertThat(items.countUnread(OWNER)).isZero();
        assertThat(markAllRead(OWNER, Instant.now())).isZero();
        assertThat(items.findById(id).orElseThrow().getReadAt()).isNull();
    }

    @Test
    @DisplayName("an item belonging to the caller is found by id, and another owner's is not")
    void findOwnedIsScoped() {
        UUID mine = items.save(unread(OWNER)).getId();
        UUID theirs = items.save(unread(OTHER_OWNER)).getId();

        assertThat(items.findOwned(OWNER, mine)).isPresent();
        assertThat(items.findOwned(OWNER, theirs)).isEmpty();
    }

    /**
     * Dismissing is not deleting: a client holding a link to an item it has just dismissed must still
     * be able to open it, even though the list no longer shows it.
     */
    @Test
    @DisplayName("a dismissed item is still retrievable by id")
    void dismissedItemIsStillRetrievable() {
        InboxItemEntity dismissed = unread(OWNER);
        dismissed.setDismissedAt(Instant.now());
        UUID id = items.save(dismissed).getId();

        assertThat(items.findOwned(OWNER, id)).isPresent();
    }

    private long markAllRead(String owner, Instant at) {
        return transactions.execute(status -> items.markAllRead(owner, at));
    }

    private static InboxItemEntity unread(String owner) {
        InboxItemEntity item = InboxItemEntity.builder()
                .ownerUserId(owner)
                .templateKey("welcome")
                .category("account")
                .categoryClass(CategoryKind.TRANSACTIONAL)
                .priority((short) 50)
                .locale("en")
                .createdAt(Instant.now())
                .build();
        item.setId(UUID.randomUUID());
        return item;
    }
}
