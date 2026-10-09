package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.service.exception.InboxItemNotFoundException;
import ru.ludwigandreas.notification.service.inbox.InboxService;
import ru.ludwigandreas.notification.service.mapper.InboxItemMapper;
import ru.ludwigandreas.notification.service.model.InboxItemView;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The inbox's read-state transitions: that each is idempotent, that each is monotonic, and that
 * every one of them is scoped to the caller.
 *
 * <h2>Why idempotence is asserted rather than assumed</h2>
 *
 * <p>These endpoints are called from a browser over a connection that drops, so every one of them
 * will be retried in production whether or not anybody designed for it. A second "mark read" that
 * rewrote {@code readAt} would move a timestamp the retention window is measured from - so a
 * notification somebody read in January could still be sitting in their inbox in March because a
 * retry kept pushing its anchor forward.
 *
 * <h2>Why the scoping assertions are here and not only in the integration test</h2>
 *
 * <p>{@code InboxScopingIT} proves the HTTP behaviour: that a foreign item and a missing item are
 * indistinguishable on the wire. These prove the layer below it - that the owner reaches the query
 * at all. Both are needed, because a service that passed the caller's subject to a repository that
 * ignored it would satisfy the HTTP test on a database containing one user's data and fail in
 * production.
 */
class InboxServiceTest {

    private static final String OWNER = "owner-subject";
    private static final UUID ITEM_ID = UUID.fromString("0f8a1c2d-3e4f-5061-7283-94a5b6c7d8e9");

    private InboxItemRepository items;
    private InboxItemContentRepository contents;
    private InboxService service;

    @BeforeEach
    void setUp() {
        items = mock(InboxItemRepository.class);
        contents = mock(InboxItemContentRepository.class);
        InboxItemMapper mapper = Mappers.getMapper(InboxItemMapper.class);
        service = new InboxService(items, contents, mapper);

        when(items.save(any(InboxItemEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(contents.findById(any())).thenReturn(Optional.empty());

        SecurityContextHolder.getContext().setAuthentication(
                TestPrincipalBuilder.user(OWNER).authentication());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("marking an item read sets both read and seen")
    void readImpliesSeen() {
        given(unreadItem());

        InboxItemView view = service.markRead(ITEM_ID);

        assertThat(view.readAt()).isNotNull();
        assertThat(view.seenAt()).isNotNull();
        assertThat(view.read()).isTrue();
    }

    /**
     * The instant must survive a repeat. Asserted by value rather than by counting saves, because a
     * second save that rewrote the same field to a new instant would pass a call-count assertion.
     */
    @Test
    @DisplayName("marking an already-read item read again leaves the original instant alone")
    void markReadIsIdempotent() {
        Instant originallyRead = Instant.parse("2026-01-04T09:00:00Z");
        InboxItemEntity item = unreadItem();
        item.setReadAt(originallyRead);
        item.setSeenAt(originallyRead);
        given(item);

        InboxItemView view = service.markRead(ITEM_ID);

        assertThat(view.readAt()).isEqualTo(originallyRead);
        assertThat(view.seenAt()).isEqualTo(originallyRead);
    }

    /**
     * Seen must not be back-dated by a later read. A client can show an item in a list long before
     * the recipient opens it, and the two instants are separate facts.
     */
    @Test
    @DisplayName("reading an already-seen item does not move the seen instant")
    void readDoesNotRewriteSeen() {
        Instant seenEarlier = Instant.parse("2026-01-04T09:00:00Z");
        InboxItemEntity item = unreadItem();
        item.setSeenAt(seenEarlier);
        given(item);

        InboxItemView view = service.markRead(ITEM_ID);

        assertThat(view.seenAt()).isEqualTo(seenEarlier);
        assertThat(view.readAt()).isNotNull().isNotEqualTo(seenEarlier);
    }

    @Test
    @DisplayName("marking an item seen does not mark it read")
    void seenDoesNotImplyRead() {
        given(unreadItem());

        InboxItemView view = service.markSeen(ITEM_ID);

        assertThat(view.seenAt()).isNotNull();
        assertThat(view.readAt()).isNull();
        assertThat(view.read()).isFalse();
    }

    /**
     * Dismissing is not reading. A recipient who swipes a notification away has dealt with it without
     * having opened it, and recording it as read would claim they saw its contents.
     */
    @Test
    @DisplayName("dismissing an item does not mark it read")
    void dismissDoesNotMarkRead() {
        given(unreadItem());

        InboxItemView view = service.dismiss(ITEM_ID);

        assertThat(view.dismissedAt()).isNotNull();
        assertThat(view.dismissed()).isTrue();
        assertThat(view.readAt()).isNull();
    }

    @Test
    @DisplayName("dismissing an already-dismissed item leaves the original instant alone")
    void dismissIsIdempotent() {
        Instant originally = Instant.parse("2026-01-04T09:00:00Z");
        InboxItemEntity item = unreadItem();
        item.setDismissedAt(originally);
        given(item);

        assertThat(service.dismiss(ITEM_ID).dismissedAt()).isEqualTo(originally);
    }

    @Test
    @DisplayName("every transition looks the item up by the caller's own subject")
    void transitionsAreScopedToTheCaller() {
        given(unreadItem());

        service.markRead(ITEM_ID);

        verify(items).findOwned(eq(OWNER), eq(ITEM_ID));
    }

    /**
     * The repository answers empty both for a missing item and for somebody else's, so one assertion
     * covers both - which is itself the point: the service cannot tell them apart either, so it
     * cannot accidentally report them differently.
     */
    @Test
    @DisplayName("an item that is not the caller's is not found")
    void foreignOrMissingItemIsNotFound() {
        when(items.findOwned(eq(OWNER), eq(ITEM_ID))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(ITEM_ID))
                .isInstanceOf(InboxItemNotFoundException.class);
    }

    @Test
    @DisplayName("mark-all-read reports how many items moved and is scoped to the caller")
    void markAllReadReportsTheCount() {
        when(items.markAllRead(eq(OWNER), any(Instant.class))).thenReturn(12L);

        assertThat(service.markAllRead().unread()).isEqualTo(12L);
        verify(items).markAllRead(eq(OWNER), any(Instant.class));
    }

    @Test
    @DisplayName("repeating mark-all-read reports zero rather than failing")
    void markAllReadIsIdempotent() {
        when(items.markAllRead(eq(OWNER), any(Instant.class))).thenReturn(0L);

        assertThat(service.markAllRead().unread()).isZero();
    }

    @Test
    @DisplayName("the unread count is read for the caller's own subject")
    void unreadCountIsScopedToTheCaller() {
        when(items.countUnread(OWNER)).thenReturn(3L);

        assertThat(service.summary().unread()).isEqualTo(3L);
    }

    /**
     * {@code SecurityPrincipals.require()} throws rather than returning a default, and this asserts
     * that the inbox inherits that. A service that fell back to an empty or anonymous subject would
     * run an unscoped query, which is the one failure mode worth a test of its own.
     */
    @Test
    @DisplayName("with no authenticated caller nothing is read or written")
    void noCallerMeansNoQuery() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.summary()).isInstanceOf(RuntimeException.class);
        verify(items, org.mockito.Mockito.never()).countUnread(any());
    }

    private void given(InboxItemEntity item) {
        when(items.findOwned(eq(OWNER), eq(ITEM_ID))).thenReturn(Optional.of(item));
    }

    private static InboxItemEntity unreadItem() {
        InboxItemEntity item = InboxItemEntity.builder()
                .ownerUserId(OWNER)
                .templateKey("welcome")
                .category("account")
                .categoryClass(CategoryKind.TRANSACTIONAL)
                .priority((short) 50)
                .locale("en")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        item.setId(ITEM_ID);
        return item;
    }
}
