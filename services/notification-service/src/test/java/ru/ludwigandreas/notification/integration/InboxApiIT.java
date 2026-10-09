package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.InboxItemContentEntity;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;

/**
 * The recipient-facing inbox API over HTTP.
 *
 * <p>Every request here runs as {@link TestPrincipals#recipient(String)} - an authenticated person
 * with <b>no notification role at all</b>. That is the point rather than a convenience: reading one's
 * own notifications is not a privilege anybody grants, and a test that ran as an admin would pass
 * equally well against endpoints accidentally gated on a role, proving nothing about the only caller
 * this API exists for.
 */
@AutoConfigureMockMvc
class InboxApiIT extends NotificationTestBase {

    private static final String OWNER = "recipient-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InboxItemRepository items;

    @Autowired
    private InboxItemContentRepository contents;

    @BeforeEach
    void resetState() {
        contents.deleteAll();
        items.deleteAll();
    }

    @Test
    @DisplayName("a recipient with no role lists their own inbox")
    void listIsOpenToAnyAuthenticatedPerson() throws Exception {
        store(OWNER, "First");

        mockMvc.perform(get("/api/v1/inbox").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("an unauthenticated request reads nothing")
    void unauthenticatedIsRefused() throws Exception {
        store(OWNER, "First");

        mockMvc.perform(get("/api/v1/inbox"))
                .andExpect(status().is4xxClientError());
    }

    /**
     * The page envelope is the platform's, so the caller's absolute position is stated rather than
     * inferred from the size of what came back.
     */
    @Test
    @DisplayName("the list uses the platform page envelope and defaults to newest first")
    void pagingAndDefaultOrder() throws Exception {
        store(OWNER, "Oldest", Instant.parse("2026-01-01T00:00:00Z"));
        store(OWNER, "Middle", Instant.parse("2026-02-01T00:00:00Z"));
        store(OWNER, "Newest", Instant.parse("2026-03-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/inbox").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].subject").doesNotExist())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.offset").value(0));

        mockMvc.perform(get("/api/v1/inbox?$top=1&$skip=1")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.content[0].templateKey").value("welcome"));
    }

    /**
     * Bodies are omitted from a page and present on a single fetch. A fifty-item page must not be
     * fifty multi-kilobyte bodies the client throws away, which is the reason content is a separate
     * table in the first place.
     */
    @Test
    @DisplayName("a page carries no bodies and a single fetch does")
    void contentIsOnlyOnASingleFetch() throws Exception {
        UUID id = store(OWNER, "Welcome aboard");

        mockMvc.perform(get("/api/v1/inbox").with(TestPrincipals.recipient(OWNER)))
                .andExpect(jsonPath("$.content[0].subject").doesNotExist())
                .andExpect(jsonPath("$.content[0].bodyHtml").doesNotExist());

        mockMvc.perform(get("/api/v1/inbox/" + id).with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Welcome aboard"))
                .andExpect(jsonPath("$.bodyHtml").value("<p>Body</p>"));
    }

    @Test
    @DisplayName("the unread count is its own endpoint and carries no content")
    void unreadCount() throws Exception {
        store(OWNER, "One");
        store(OWNER, "Two");

        mockMvc.perform(get("/api/v1/inbox/unread-count").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(2))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    @DisplayName("a caller with nothing unread gets zero rather than an error")
    void unreadCountIsZeroWhenEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/inbox/unread-count").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("marking read sets both instants and is idempotent over HTTP")
    void markRead() throws Exception {
        UUID id = store(OWNER, "One");

        String first = mockMvc.perform(post("/api/v1/inbox/" + id + "/read")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty())
                .andExpect(jsonPath("$.seenAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/inbox/" + id + "/read")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Byte-identical, which is stronger than both being 200: a retry must not move the instant
        // the retention window is measured from.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("mark-all-read reports the count, then zero")
    void markAllRead() throws Exception {
        store(OWNER, "One");
        store(OWNER, "Two");
        store(OWNER, "Three");

        mockMvc.perform(post("/api/v1/inbox/read").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(3));

        mockMvc.perform(post("/api/v1/inbox/read").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("a dismissed item leaves the list but stays retrievable by id")
    void dismiss() throws Exception {
        UUID id = store(OWNER, "One");

        mockMvc.perform(post("/api/v1/inbox/" + id + "/dismiss")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(true))
                // Dismissing is not reading: the recipient dealt with it without opening it.
                .andExpect(jsonPath("$.readAt").doesNotExist());

        mockMvc.perform(get("/api/v1/inbox").with(TestPrincipals.recipient(OWNER)))
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/inbox/" + id).with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/inbox/unread-count").with(TestPrincipals.recipient(OWNER)))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("marking seen does not mark read")
    void markSeen() throws Exception {
        UUID id = store(OWNER, "One");

        mockMvc.perform(post("/api/v1/inbox/" + id + "/seen")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seenAt").isNotEmpty())
                .andExpect(jsonPath("$.read").value(false));
    }

    /**
     * An inbox write is not a long-running operation. No status-resource header, no
     * {@code Retry-After}, no envelope - the platform's operation contract is for work that actually
     * runs, and a client polling for something already finished is the failure this asserts against.
     */
    @Test
    @DisplayName("no inbox response carries an operation envelope or its headers")
    void notALongRunningOperation() throws Exception {
        UUID id = store(OWNER, "One");

        mockMvc.perform(post("/api/v1/inbox/" + id + "/read")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").doesNotExist());

        mockMvc.perform(post("/api/v1/inbox/read").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    /**
     * Filtering works on the published surface, and the owner is not on it. The rejection is the same
     * one the service already gives for a filter on a recipient address, which is what makes "the
     * owner is unfilterable" a property of the filter engine rather than of this endpoint.
     */
    @Test
    @DisplayName("a filter on read state works and a filter on the owner is rejected")
    void filtering() throws Exception {
        UUID read = store(OWNER, "Read one");
        store(OWNER, "Unread one");
        mockMvc.perform(post("/api/v1/inbox/" + read + "/read")
                .with(TestPrincipals.recipient(OWNER)));

        mockMvc.perform(get("/api/v1/inbox?$filter=readAt eq null")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get("/api/v1/inbox?$filter=ownerUserId eq 'somebody-else'")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the published filterable surface omits the owner")
    void metadataOmitsTheOwner() throws Exception {
        mockMvc.perform(get("/api/v1/filter-metadata/notification-inbox")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties[?(@.path == 'ownerUserId')]").isEmpty())
                // The control: the document is not simply empty.
                .andExpect(jsonPath("$.properties[?(@.path == 'category')]").isNotEmpty());
    }

    private UUID store(String owner, String subject) {
        return store(owner, subject, Instant.now());
    }

    private UUID store(String owner, String subject, Instant createdAt) {
        UUID id = UUID.randomUUID();
        InboxItemEntity item = InboxItemEntity.builder()
                .ownerUserId(owner)
                .templateKey("welcome")
                .category("account")
                .categoryClass(CategoryKind.TRANSACTIONAL)
                .priority((short) 50)
                .locale("en")
                .createdAt(createdAt)
                .build();
        item.setId(id);
        items.save(item);

        InboxItemContentEntity content = InboxItemContentEntity.builder()
                .subject(subject)
                .bodyHtml("<p>Body</p>")
                .bodyText("Body")
                .renderedAt(createdAt)
                .build();
        content.setId(id);
        contents.save(content);
        return id;
    }
}
