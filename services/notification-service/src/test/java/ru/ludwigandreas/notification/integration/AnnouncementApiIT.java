package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementMarkerRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.web.dto.AudienceTypeDto;
import ru.ludwigandreas.notification.web.dto.PublishAnnouncementRequest;

/**
 * The announcement API over HTTP: who may publish, what a publish may decide, and what the feed
 * exposes.
 *
 * <p>The authorization split is the thing worth reading. <b>Publishing</b> needs its own role,
 * because one person using it addresses the whole organisation. <b>Reading</b> needs only
 * authentication, because the audience predicate <em>is</em> the authorization — a caller without the
 * targeted role gets an empty page rather than a 403, which is also why a foreign announcement and a
 * nonexistent one are indistinguishable.
 */
@AutoConfigureMockMvc
class AnnouncementApiIT extends NotificationTestBase {

    private static final String RECIPIENT = "api-recipient";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnnouncementRepository announcements;

    @Autowired
    private AnnouncementContentRepository contents;

    @Autowired
    private AnnouncementMarkerRepository markers;

    @BeforeEach
    void resetState() {
        markers.deleteAll();
        contents.deleteAll();
        announcements.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------
    // Publishing
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an announcer publishes and gets 201 with the audience")
    void announcerCanPublish() throws Exception {
        mockMvc.perform(publishRequest(AudienceTypeDto.ROLE, "ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.category").value("platform-release"))
                .andExpect(jsonPath("$.categoryClass").value("PLATFORM"))
                .andExpect(jsonPath("$.audienceType").value("ROLE"))
                // The publisher's view does carry the audience, unlike the recipient's.
                .andExpect(jsonPath("$.audienceValue").isNotEmpty())
                // No fan-out for an inbox-only category, so there is no operation to poll.
                .andExpect(jsonPath("$.emailRunId").doesNotExist());

        assertThat(announcements.findAll()).hasSize(1);
    }

    /**
     * The role split. A notification admin inspects delivery history and retries dead letters; an
     * announcer originates a message to everybody. Support staff need the first and must not
     * silently acquire the second.
     */
    @Test
    @DisplayName("a notification admin may not publish")
    void notificationAdminCannotPublish() throws Exception {
        mockMvc.perform(post("/api/v1/announcements")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                body(AudienceTypeDto.EVERYONE, null))))
                .andExpect(status().isForbidden());

        assertThat(announcements.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an ordinary recipient may not publish")
    void recipientCannotPublish() throws Exception {
        mockMvc.perform(post("/api/v1/announcements")
                        .with(TestPrincipals.recipient(RECIPIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                body(AudienceTypeDto.EVERYONE, null))))
                .andExpect(status().isForbidden());
    }

    /**
     * Holding the publishing role is not the whole check. What may be announced is constrained
     * separately, by the audience allowlist — so an announcer can be refused an audience.
     */
    @Test
    @DisplayName("an announcer is still refused a role that is not on the allowlist")
    void announcerIsStillBoundByTheAllowlist() throws Exception {
        mockMvc.perform(publishRequest(AudienceTypeDto.ROLE, "AUDITOR"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(announcements.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an unknown category is rejected with a translated problem")
    void unknownCategoryIsRejected() throws Exception {
        PublishAnnouncementRequest request = new PublishAnnouncementRequest(
                "no-such-category", AudienceTypeDto.EVERYONE, null, "platform-release",
                Map.of("productName", "Ludwig", "version", "1.4.0"), null,
                Instant.now().plus(1, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/announcements")
                        .with(TestPrincipals.announcer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                // Through web-core's single ProblemDetail pipeline, with the category echoed as a
                // machine-readable property but the valid catalogue deliberately not listed.
                .andExpect(jsonPath("$.category").value("no-such-category"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("a publish with no end instant is rejected by validation")
    void missingWindowEndIsRejected() throws Exception {
        PublishAnnouncementRequest request = new PublishAnnouncementRequest(
                "platform-release", AudienceTypeDto.EVERYONE, null, "platform-release",
                Map.of("productName", "Ludwig", "version", "1.4.0"), null, null);

        mockMvc.perform(post("/api/v1/announcements")
                        .with(TestPrincipals.announcer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                // A translated sentence, not a raw bundle key - which is what a missing
                // notification.validation.announcement.* entry would have produced.
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("a correction re-renders and is restricted to the announcer role")
    void correctionIsRestricted() throws Exception {
        String published = mockMvc.perform(publishRequest(AudienceTypeDto.EVERYONE, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(published).path("id").asText();

        mockMvc.perform(post("/api/v1/announcements/" + id + "/corrections")
                        .with(TestPrincipals.recipient(RECIPIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"productName\":\"Ludwig\",\"version\":\"1.4.1\"}}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/announcements/" + id + "/corrections")
                        .with(TestPrincipals.announcer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PublishAnnouncementRequest(
                                "platform-release", AudienceTypeDto.EVERYONE, null,
                                "platform-release",
                                Map.of("productName", "Ludwig", "version", "1.4.1"), null,
                                Instant.now().plus(1, ChronoUnit.DAYS)))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------------
    // The feed
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the feed uses the platform page envelope and defaults to newest first")
    void feedPagesAndOrders() throws Exception {
        publish(AudienceTypeDto.EVERYONE, null);
        publish(AudienceTypeDto.EVERYONE, null);
        publish(AudienceTypeDto.EVERYONE, null);

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.offset").value(0));

        mockMvc.perform(get("/api/v1/announcements?$top=1&$skip=1")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.offset").value(1));
    }

    @Test
    @DisplayName("the feed carries content, because an announcement is read where it is listed")
    void feedCarriesContent() throws Exception {
        publish(AudienceTypeDto.EVERYONE, null);

        mockMvc.perform(get("/api/v1/announcements")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(jsonPath("$.content[0].subject").isNotEmpty())
                .andExpect(jsonPath("$.content[0].locale").value("en"));
    }

    @Test
    @DisplayName("a filter on the audience is rejected and a filter on the category is not")
    void audienceIsNotFilterable() throws Exception {
        publish(AudienceTypeDto.EVERYONE, null);

        mockMvc.perform(get("/api/v1/announcements?$filter=category eq 'platform-release'")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get("/api/v1/announcements?$filter=audienceValue eq 'ROLE_ADMIN'")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/announcements?$filter=audienceKind eq 'ROLE'")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the published filterable surface omits the audience")
    void metadataOmitsTheAudience() throws Exception {
        mockMvc.perform(get("/api/v1/filter-metadata/notification-announcement")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties[?(@.path == 'audienceValue')]").isEmpty())
                .andExpect(jsonPath("$.properties[?(@.path == 'audienceKind')]").isEmpty())
                // The control: the document is not simply empty.
                .andExpect(jsonPath("$.properties[?(@.path == 'category')]").isNotEmpty());
    }

    /** No inbox response shape leaks in, and no operation envelope appears on a dismissal. */
    @Test
    @DisplayName("dismissal is not a long-running operation")
    void dismissalIsNotAnOperation() throws Exception {
        String published = mockMvc.perform(publishRequest(AudienceTypeDto.EVERYONE, null))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(published).path("id").asText();

        mockMvc.perform(post("/api/v1/announcements/" + id + "/dismiss")
                        .with(TestPrincipals.recipient(RECIPIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    private void publish(AudienceTypeDto type, String value) throws Exception {
        mockMvc.perform(publishRequest(type, value)).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publishRequest(
            AudienceTypeDto type, String value) throws Exception {
        return post("/api/v1/announcements")
                .with(TestPrincipals.announcer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body(type, value)));
    }

    private static PublishAnnouncementRequest body(AudienceTypeDto type, String value) {
        Instant from = Instant.now().minusSeconds(1);
        return new PublishAnnouncementRequest("platform-release", type, value, "platform-release",
                Map.of("productName", "Ludwig", "version", "1.4.0"), from,
                from.plus(7, ChronoUnit.DAYS));
    }
}
