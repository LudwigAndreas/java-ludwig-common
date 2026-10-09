package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MvcResult;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;

/**
 * That a caller can only ever reach their own inbox, and cannot learn anything about anybody else's.
 *
 * <h2>Why "both are 404" is not a strong enough assertion</h2>
 *
 * <p>The tests below compare the two whole response bodies rather than asserting that each is a 404.
 * Two 404s that differ in their {@code detail} - "this notification is not yours" versus "no such
 * notification" - are just as much an oracle as a 403 would be, and that difference is exactly what a
 * well-meaning change to an error message introduces. Comparing the bodies is the only form of this
 * assertion that keeps working after somebody improves the wording.
 *
 * <p>Three fields are normalised before the comparison, and each is normalised because it
 * <em>cannot</em> carry information about another person's item. {@code instance} is the request URI,
 * so it echoes the id the caller themselves chose to send; {@code timestamp} is the clock; and
 * {@code traceId} is this request's own tracing id, whose presence depends on whether a trace context
 * was active rather than on whether the item exists. Everything that could distinguish the two cases
 * - {@code type}, {@code title}, {@code status}, {@code code} and the wording of {@code detail} - is
 * compared.
 *
 * <p>The reason an oracle matters more here than for a delivery row is what the fact <em>is</em>:
 * "an inbox item with this id exists" is "this person has been told about something", and the
 * subject would reasonably consider that private from everybody, support included.
 */
@AutoConfigureMockMvc
class InboxScopingIT extends NotificationTestBase {

    private static final String OWNER = "recipient-own";
    private static final String OTHER = "recipient-other";

    /** An id that has never existed, to compare a real foreign item against. */
    private static final UUID NEVER_EXISTED =
            UUID.fromString("00000000-0000-4000-8000-000000000000");

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
    @DisplayName("a foreign item and an id that never existed produce identical responses")
    void foreignAndMissingAreIndistinguishable() throws Exception {
        UUID theirs = store(OTHER);

        String foreign = normalise(body(get("/api/v1/inbox/" + theirs), 404), theirs);
        String missing = normalise(body(get("/api/v1/inbox/" + NEVER_EXISTED), 404), NEVER_EXISTED);

        assertThat(foreign)
                .as("a difference here - even in wording - tells a caller which ids exist")
                .isEqualTo(missing);
    }

    @Test
    @DisplayName("the same holds for every transition, not only the read")
    void transitionsAreIndistinguishableToo() throws Exception {
        UUID theirs = store(OTHER);

        for (String action : java.util.List.of("read", "dismiss", "seen")) {
            String foreign = normalise(
                    body(post("/api/v1/inbox/" + theirs + "/" + action), 404), theirs);
            String missing = normalise(
                    body(post("/api/v1/inbox/" + NEVER_EXISTED + "/" + action), 404), NEVER_EXISTED);
            assertThat(foreign).as("the %s transition", action).isEqualTo(missing);
        }
    }

    /**
     * The refusal must not have been a side effect of the item being untouched. This asserts the
     * other person's item is genuinely unchanged - a 404 returned after the write would be worse
     * than a 403.
     */
    @Test
    @DisplayName("a refused transition leaves the other person's item untouched")
    void refusedTransitionWritesNothing() throws Exception {
        UUID theirs = store(OTHER);

        mockMvc.perform(post("/api/v1/inbox/" + theirs + "/read")
                        .with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isNotFound());

        InboxItemEntity reloaded = items.findById(theirs).orElseThrow();
        assertThat(reloaded.getReadAt()).isNull();
        assertThat(reloaded.getSeenAt()).isNull();
    }

    @Test
    @DisplayName("a list shows only the caller's own items")
    void listIsScoped() throws Exception {
        store(OWNER);
        store(OTHER);
        store(OTHER);

        mockMvc.perform(get("/api/v1/inbox").with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    /**
     * The count must be scoped too. A badge that counted everybody's notifications would leak the
     * platform's whole volume to every user, and is the one endpoint where a missing owner predicate
     * produces a plausible-looking number rather than an obvious error.
     */
    @Test
    @DisplayName("the unread count counts only the caller's own items")
    void countIsScoped() throws Exception {
        store(OWNER);
        store(OTHER);
        store(OTHER);
        store(OTHER);

        mockMvc.perform(get("/api/v1/inbox/unread-count").with(TestPrincipals.recipient(OWNER)))
                .andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    @DisplayName("mark-all-read moves only the caller's own items")
    void markAllReadIsScoped() throws Exception {
        store(OWNER);
        UUID theirs = store(OTHER);

        mockMvc.perform(post("/api/v1/inbox/read").with(TestPrincipals.recipient(OWNER)))
                .andExpect(jsonPath("$.unread").value(1));

        assertThat(items.findById(theirs).orElseThrow().getReadAt()).isNull();
    }

    /**
     * No response carries the owner. A field echoing it would be the one a client sends back, and
     * the parameter this API must never accept.
     */
    @Test
    @DisplayName("no inbox response carries an owner field")
    void responsesCarryNoOwner() throws Exception {
        UUID mine = store(OWNER);

        mockMvc.perform(get("/api/v1/inbox/" + mine).with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.owner").doesNotExist());

        mockMvc.perform(get("/api/v1/inbox").with(TestPrincipals.recipient(OWNER)))
                .andExpect(jsonPath("$.content[0].ownerUserId").doesNotExist());
    }

    /**
     * Replaces the caller's own id and the clock, leaving everything that could distinguish the two
     * cases intact. Not a weakening of the assertion: the id came from the caller's own request line
     * and the timestamp is the time.
     */
    private static String normalise(String responseBody, UUID id) {
        return responseBody
                .replace(id.toString(), "{id}")
                .replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"{t}\"")
                // Removed rather than masked, because the field is present on some responses and
                // absent on others depending on whether a trace context was active - a difference
                // that is about this request's own instrumentation and not about the item.
                .replaceAll(",?\"traceId\":\"[^\"]+\"", "");
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request.with(TestPrincipals.recipient(OWNER)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private UUID store(String owner) {
        UUID id = UUID.randomUUID();
        InboxItemEntity item = InboxItemEntity.builder()
                .ownerUserId(owner)
                .templateKey("welcome")
                .category("account")
                .categoryClass(CategoryKind.TRANSACTIONAL)
                .priority((short) 50)
                .locale("en")
                .createdAt(Instant.now())
                .build();
        item.setId(id);
        items.save(item);
        return id;
    }
}
