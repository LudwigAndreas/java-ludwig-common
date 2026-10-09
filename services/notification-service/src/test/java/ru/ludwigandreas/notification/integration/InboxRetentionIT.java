package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.InboxItemContentEntity;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.service.retention.RetentionService;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * The inbox's retention, and the one property of it that matters more than the rest.
 *
 * <p><b>An unread item is never purged by age.</b> Every other window in this service runs from
 * creation, because a delivery is a record of something the service did. An inbox item is a document
 * its owner may not have opened yet, and an age-anchored purge would silently delete notifications
 * out from under people who were on holiday - which is the exact failure the inbox exists to
 * prevent. {@link #unreadItemSurvivesIndefinitely()} is the assertion that holds that line, and it
 * is written to fail loudly if anybody ever "simplifies" the purge predicate to use
 * {@code created_at}.
 *
 * <p>The ceiling that <em>does</em> discard unread items is tested separately and is off by default,
 * which is the point of it being a separate property.
 */
class InboxRetentionIT extends NotificationTestBase {

    private static final String OWNER = "retention-owner";

    @Autowired
    private InboxItemRepository items;

    @Autowired
    private InboxItemContentRepository contents;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private NotificationProperties properties;

    private Duration originalCeiling;

    @BeforeEach
    void resetState() {
        contents.deleteAll();
        items.deleteAll();
        originalCeiling = properties.getRetention().getInboxUnreadMaxAge();
    }

    @AfterEach
    void restoreCeiling() {
        properties.getRetention().setInboxUnreadMaxAge(originalCeiling);
    }

    @Test
    @DisplayName("an item read longer ago than the window is purged, with its content")
    void readItemIsPurged() {
        Instant longAgo = Instant.now()
                .minus(properties.getRetention().getInboxTtl())
                .minus(Duration.ofDays(1));
        UUID id = store(longAgo, longAgo);

        assertThat(retentionService.purgeInbox(Instant.now())).isEqualTo(1L);

        assertThat(items.findById(id)).isEmpty();
        assertThat(contents.findById(id))
                .as("content must go with the item rather than being orphaned")
                .isEmpty();
    }

    @Test
    @DisplayName("a dismissed item is purged on the same window as a read one")
    void dismissedItemIsPurged() {
        Instant longAgo = Instant.now()
                .minus(properties.getRetention().getInboxTtl())
                .minus(Duration.ofDays(1));
        InboxItemEntity item = newItem(longAgo);
        item.setDismissedAt(longAgo);
        UUID id = persist(item, longAgo);

        assertThat(retentionService.purgeInbox(Instant.now())).isEqualTo(1L);
        assertThat(items.findById(id)).isEmpty();
    }

    /**
     * The line this whole class exists to hold. The item is far older than any window configured, and
     * it must still be there.
     */
    @Test
    @DisplayName("an unread item older than every window is not purged, whatever its age")
    void unreadItemSurvivesIndefinitely() {
        Instant ancient = Instant.now().minus(Duration.ofDays(3650));
        UUID id = store(ancient, null);

        assertThat(retentionService.purgeInbox(Instant.now()))
                .as("an item nobody has read must not be purged by the read-anchored window")
                .isZero();
        assertThat(retentionService.purgeUnreadInbox(Instant.now()))
                .as("and not by the ceiling either, because the ceiling is unset by default")
                .isZero();

        assertThat(items.findById(id)).isPresent();
        assertThat(contents.findById(id)).isPresent();
    }

    @Test
    @DisplayName("an item read recently is left alone")
    void recentlyReadItemIsKept() {
        Instant now = Instant.now();
        UUID id = store(now.minus(Duration.ofDays(1)), now.minus(Duration.ofHours(1)));

        assertThat(retentionService.purgeInbox(now)).isZero();
        assertThat(items.findById(id)).isPresent();
    }

    @Test
    @DisplayName("the unread ceiling does nothing until it is configured")
    void ceilingIsOffByDefault() {
        store(Instant.now().minus(Duration.ofDays(3650)), null);

        assertThat(properties.getRetention().getInboxUnreadMaxAge())
                .as("shipping a default here would discard unread notifications on upgrade")
                .isNull();
        assertThat(retentionService.purgeUnreadInbox(Instant.now())).isZero();
    }

    /**
     * With a ceiling configured the unread item goes - and the two counts stay separable, which is
     * what lets an operator see "we discarded N unread notifications" as its own number.
     */
    @Test
    @DisplayName("a configured ceiling purges an unread item, and the two counts move independently")
    void ceilingPurgesUnreadItemsAndIsCountedSeparately() {
        properties.getRetention().setInboxUnreadMaxAge(Duration.ofDays(30));

        Instant old = Instant.now().minus(Duration.ofDays(60));
        UUID unread = store(old, null);
        UUID readRecently = store(old, Instant.now().minus(Duration.ofHours(1)));

        // The read-anchored sweep sees nothing: the only old item is unread, and the read one was
        // read an hour ago.
        assertThat(retentionService.purgeInbox(Instant.now())).isZero();
        // The ceiling sweep sees exactly the unread one.
        assertThat(retentionService.purgeUnreadInbox(Instant.now())).isEqualTo(1L);

        assertThat(items.findById(unread)).isEmpty();
        assertThat(items.findById(readRecently)).isPresent();
    }

    /**
     * A ceiling must not reach an item its owner has already dealt with - that item belongs to the
     * read-anchored window, and purging it here would double-count it.
     */
    @Test
    @DisplayName("the ceiling ignores items that have been read or dismissed")
    void ceilingIgnoresSettledItems() {
        properties.getRetention().setInboxUnreadMaxAge(Duration.ofDays(30));
        Instant old = Instant.now().minus(Duration.ofDays(60));

        store(old, old);

        assertThat(retentionService.purgeUnreadInbox(Instant.now())).isZero();
    }

    private UUID store(Instant createdAt, Instant readAt) {
        InboxItemEntity item = newItem(createdAt);
        item.setReadAt(readAt);
        item.setSeenAt(readAt);
        return persist(item, createdAt);
    }

    private UUID persist(InboxItemEntity item, Instant renderedAt) {
        UUID id = UUID.randomUUID();
        item.setId(id);
        items.save(item);

        InboxItemContentEntity content = InboxItemContentEntity.builder()
                .subject("Subject")
                .bodyHtml("<p>Body</p>")
                .bodyText("Body")
                .renderedAt(renderedAt)
                .build();
        content.setId(id);
        contents.save(content);
        return id;
    }

    private static InboxItemEntity newItem(Instant createdAt) {
        return InboxItemEntity.builder()
                .ownerUserId(OWNER)
                .templateKey("welcome")
                .category("account")
                .categoryClass(CategoryKind.TRANSACTIONAL)
                .priority((short) 50)
                .locale("en")
                .createdAt(createdAt)
                .build();
    }
}
