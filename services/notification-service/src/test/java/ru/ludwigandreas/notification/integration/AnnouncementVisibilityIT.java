package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementMarkerRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAdminService;
import ru.ludwigandreas.notification.service.model.AudienceType;

/**
 * Who can see an announcement, and when that changes.
 *
 * <p>This is the security-critical test of the change. Unlike the inbox — whose correctness rests on
 * the obviously-right {@code owner = me} — announcement visibility is <b>derived</b> from role
 * membership, so it is the kind of predicate that can be wrong in a way nothing else notices.
 *
 * <h2>The bug this test exists to catch, which it did</h2>
 *
 * <p>A principal's roles are normalised to the {@code ROLE_} prefix at the edge of the service, while
 * the configured allowlist and the directory's own {@code role_code} column hold the bare code. The
 * first implementation compared the stored bare {@code ADMIN} against a prefixed {@code ROLE_ADMIN}
 * and therefore matched <b>nothing</b>: a role-targeted announcement published cleanly, reported
 * success, and was visible to nobody at all. No error, no empty-result warning, no failing unit test
 * — only this kind of test finds it.
 *
 * <p>So {@link #aCallerHoldingTheRoleSeesIt()} and {@link #aCallerWithoutTheRoleDoesNot()} are a
 * pair, and neither is sufficient alone: the first would pass against a predicate that showed
 * everything, and the second against one that showed nothing.
 */
@AutoConfigureMockMvc
class AnnouncementVisibilityIT extends NotificationTestBase {

    private static final String SUBJECT = "visibility-subject";
    private static final Map<String, Object> VARIABLES =
            Map.of("productName", "Ludwig", "version", "1.4.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnnouncementAdminService adminService;

    @Autowired
    private AnnouncementRepository announcements;

    @Autowired
    private AnnouncementContentRepository contents;

    @Autowired
    private AnnouncementMarkerRepository markers;

    @Autowired
    private InboxItemRepository inboxItems;

    @Autowired
    private InboxItemContentRepository inboxContents;

    @BeforeEach
    void resetState() {
        markers.deleteAll();
        contents.deleteAll();
        announcements.deleteAll();
        inboxContents.deleteAll();
        inboxItems.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------
    // Role membership
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a caller holding the targeted role sees the announcement")
    void aCallerHoldingTheRoleSeesIt() throws Exception {
        publishToRole("ADMIN");

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipientWithRoles(SUBJECT, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    /** The other half of the pair. Without it, a predicate showing everything would pass above. */
    @Test
    @DisplayName("a caller without the targeted role does not see it")
    void aCallerWithoutTheRoleDoesNot() throws Exception {
        publishToRole("ADMIN");

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipientWithRoles(SUBJECT, "ROLE_SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("a caller holding no roles at all still sees an EVERYONE announcement")
    void everyoneReachesACallerWithNoRoles() throws Exception {
        publishToEveryone();

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    /**
     * Revocation is the whole argument for resolving visibility at read time. The same subject asks
     * twice, differing only in their roles — no purge, no sweep, no background job between the two
     * requests.
     */
    @Test
    @DisplayName("revoking the role removes the announcement on the very next read")
    void revokingTheRoleRemovesItImmediately() throws Exception {
        publishToRole("ADMIN");

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipientWithRoles(SUBJECT, "ROLE_ADMIN")))
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipientWithRoles(SUBJECT)))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/v1/announcements/outstanding-count")
                        .with(TestPrincipals.recipientWithRoles(SUBJECT)))
                .andExpect(jsonPath("$.outstanding").value(0));

        assertThat(announcements.findAll())
                .as("nothing was purged or rewritten; only the question's answer changed")
                .hasSize(1);
    }

    /**
     * The converse, and the behaviour a per-recipient fan-out cannot produce: somebody granted the
     * role afterwards sees what was published before they held it.
     */
    @Test
    @DisplayName("granting the role reveals announcements published before it was held")
    void grantingTheRoleRevealsTheBacklog() throws Exception {
        publishToRole("ADMIN");

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipientWithRoles(SUBJECT)))
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipientWithRoles(SUBJECT, "ROLE_ADMIN")))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("fetching another audience's announcement by id is the same 404 as one that never existed")
    void foreignAnnouncementIsIndistinguishableFromMissing() throws Exception {
        UUID theirs = publishToRole("ADMIN");
        UUID never = UUID.fromString("00000000-0000-4000-8000-000000000000");

        String foreign = body(get("/api/v1/announcements/" + theirs), theirs);
        String missing = body(get("/api/v1/announcements/" + never), never);

        assertThat(foreign)
                .as("a difference here lets a caller map which roles the platform addresses")
                .isEqualTo(missing);
    }

    // ---------------------------------------------------------------------------------------------
    // The visibility window
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an announcement whose window has not opened is invisible")
    void futureAnnouncementIsInvisible() {
        Instant from = Instant.now().plus(1, ChronoUnit.DAYS);
        adminService.publish("platform-release", AudienceType.EVERYONE, null, "platform-release",
                VARIABLES, from, from.plus(1, ChronoUnit.DAYS));

        assertThat(visibleCountFor()).isZero();
    }

    @Test
    @DisplayName("an announcement whose window has closed is invisible")
    void expiredAnnouncementIsInvisible() {
        Instant from = Instant.now().minus(10, ChronoUnit.DAYS);
        adminService.publish("platform-release", AudienceType.EVERYONE, null, "platform-release",
                VARIABLES, from, from.plus(1, ChronoUnit.DAYS));

        assertThat(visibleCountFor()).isZero();
        assertThat(announcements.findAll())
                .as("invisible is not purged; retention is a separate, later decision")
                .hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------
    // Dismissal
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("dismissing removes it for that caller alone and writes exactly one row")
    void dismissalIsPerCallerAndLazy() throws Exception {
        UUID id = publishToEveryone();

        assertThat(markers.findAll())
                .as("nobody has dismissed anything yet, so there are no per-recipient rows")
                .isEmpty();

        mockMvc.perform(post("/api/v1/announcements/" + id + "/dismiss")
                        .with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(true));

        assertThat(markers.findAll()).hasSize(1);

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipient("somebody-else")))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("a dismissed announcement is still retrievable by id")
    void dismissedIsStillRetrievable() throws Exception {
        UUID id = publishToEveryone();
        mockMvc.perform(post("/api/v1/announcements/" + id + "/dismiss")
                .with(TestPrincipals.recipient(SUBJECT)));

        mockMvc.perform(get("/api/v1/announcements/" + id)
                        .with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(true));
    }

    @Test
    @DisplayName("dismissing twice leaves one row and the original instant")
    void dismissalIsIdempotent() throws Exception {
        UUID id = publishToEveryone();

        mockMvc.perform(post("/api/v1/announcements/" + id + "/dismiss")
                .with(TestPrincipals.recipient(SUBJECT)));
        Instant first = markers.findAll().get(0).getDismissedAt();

        mockMvc.perform(post("/api/v1/announcements/" + id + "/dismiss")
                        .with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(status().isOk());

        assertThat(markers.findAll()).hasSize(1);
        assertThat(markers.findAll().get(0).getDismissedAt()).isEqualTo(first);
    }

    // ---------------------------------------------------------------------------------------------
    // The inbox is untouched
    // ---------------------------------------------------------------------------------------------

    /**
     * The inbox's queries are constrained by owner alone, and this change must not have altered that.
     * An ArchUnit rule forbids the inbox path from depending on an announcement; this asserts the
     * observable consequence, which is what a reader actually cares about.
     */
    @Test
    @DisplayName("announcements never appear in the inbox, and the inbox never appears here")
    void theTwoFeedsDoNotLeakIntoEachOther() throws Exception {
        publishToEveryone();
        storeInboxItem(SUBJECT);

        mockMvc.perform(get("/api/v1/inbox").with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].category").value("account"));

        mockMvc.perform(get("/api/v1/announcements").with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].category").value("platform-release"));
    }

    @Test
    @DisplayName("the two counts are separate numbers")
    void theTwoCountsAreSeparate() throws Exception {
        publishToEveryone();
        storeInboxItem(SUBJECT);
        storeInboxItem(SUBJECT);

        mockMvc.perform(get("/api/v1/inbox/unread-count")
                        .with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(jsonPath("$.unread").value(2));
        mockMvc.perform(get("/api/v1/announcements/outstanding-count")
                        .with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(jsonPath("$.outstanding").value(1));
    }

    /** No audience field reaches a recipient — see {@code AnnouncementResponse}. */
    @Test
    @DisplayName("a recipient's view of an announcement carries no audience")
    void recipientViewCarriesNoAudience() throws Exception {
        UUID id = publishToRole("ADMIN");

        mockMvc.perform(get("/api/v1/announcements/" + id)
                        .with(TestPrincipals.recipientWithRoles(SUBJECT, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audienceType").doesNotExist())
                .andExpect(jsonPath("$.audienceValue").doesNotExist());
    }

    @Test
    @DisplayName("an unauthenticated request reads nothing")
    void unauthenticatedReadsNothing() throws Exception {
        publishToEveryone();

        mockMvc.perform(get("/api/v1/announcements"))
                .andExpect(status().is4xxClientError());
    }

    private long visibleCountFor() {
        try {
            MvcResult result = mockMvc.perform(get("/api/v1/announcements/outstanding-count")
                            .with(TestPrincipals.recipient(SUBJECT)))
                    .andExpect(status().isOk())
                    .andReturn();
            return Long.parseLong(result.getResponse().getContentAsString()
                    .replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                request, UUID id) throws Exception {
        MvcResult result = mockMvc.perform(request.with(TestPrincipals.recipient(SUBJECT)))
                .andExpect(status().isNotFound())
                .andReturn();
        // The id and the clock are normalised for the same reason as in InboxScopingIT: both come
        // from the caller's own request or from the time, so neither can carry information about
        // somebody else's announcement.
        return result.getResponse().getContentAsString()
                .replace(id.toString(), "{id}")
                .replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"{t}\"")
                .replaceAll(",?\"traceId\":\"[^\"]+\"", "");
    }

    private UUID publishToEveryone() {
        return publish(AudienceType.EVERYONE, null);
    }

    private UUID publishToRole(String role) {
        return publish(AudienceType.ROLE, role);
    }

    private UUID publish(AudienceType type, String value) {
        Instant from = Instant.now().minusSeconds(1);
        return adminService.publish("platform-release", type, value, "platform-release", VARIABLES,
                from, from.plus(7, ChronoUnit.DAYS)).announcement().id();
    }

    private void storeInboxItem(String owner) {
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
        inboxItems.save(item);
    }
}
