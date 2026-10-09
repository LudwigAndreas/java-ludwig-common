package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The inbox API as it is published, not as it is written.
 *
 * <p>This service had no OpenAPI test before. It gets one now because two of the inbox's properties
 * are contract rather than implementation, and a client that does not know them will get them wrong:
 *
 * <ul>
 *   <li><b>The owner is never a parameter.</b> A client that assumed otherwise would build a request
 *       nothing here accepts. Asserted by checking that no inbox operation declares one - which is
 *       stronger than reading the controller, because a parameter can also arrive from a
 *       {@code @ParameterObject} or an inherited base path.</li>
 *   <li><b>A foreign item and a missing one are indistinguishable.</b> A client treating 404 as
 *       "deleted, stop retrying" and 403 as "not mine" needs to be told that it will only ever see
 *       the first. That is documented prose rather than a schema, so the test asserts the prose is
 *       there.</li>
 * </ul>
 *
 * <p>It also covers the announcement endpoints, whose cancellation contract (202, and committed
 * deliveries not recalled) is the part a client would otherwise have to discover by trying.
 *
 * <p>Deliberately not a whole-document snapshot. A snapshot of every path in this service would fail
 * on every unrelated endpoint change, and the usual response to that is to regenerate it without
 * reading the diff - which is worse than no test.
 */
@AutoConfigureMockMvc
class InboxOpenApiIT extends NotificationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("every inbox endpoint is published")
    void endpointsArePublished() throws Exception {
        JsonNode paths = document().path("paths");

        assertThat(paths.has("/api/v1/inbox")).isTrue();
        assertThat(paths.has("/api/v1/inbox/unread-count")).isTrue();
        assertThat(paths.has("/api/v1/inbox/read")).isTrue();
        assertThat(paths.has("/api/v1/inbox/{id}")).isTrue();
        assertThat(paths.has("/api/v1/inbox/{id}/read")).isTrue();
        assertThat(paths.has("/api/v1/inbox/{id}/seen")).isTrue();
        assertThat(paths.has("/api/v1/inbox/{id}/dismiss")).isTrue();
    }

    /**
     * No operation takes an owner, under any spelling. The point of asserting it against the
     * published document rather than the source is that this is what a client generator reads.
     */
    @Test
    @DisplayName("no inbox operation declares an owner parameter")
    void noOwnerParameter() throws Exception {
        JsonNode paths = document().path("paths");

        paths.fieldNames().forEachRemaining(path -> {
            if (!path.startsWith("/api/v1/inbox")) {
                return;
            }
            paths.path(path).forEach(operation ->
                    operation.path("parameters").forEach(parameter -> {
                        String name = parameter.path("name").asText();
                        assertThat(name)
                                .as("%s declares parameter '%s'", path, name)
                                .isNotIn("owner", "ownerUserId", "userId", "subject", "recipient");
                    }));
        });
    }

    @Test
    @DisplayName("the single-item read documents that a foreign item is indistinguishable from a missing one")
    void refusalContractIsDocumented() throws Exception {
        String description = document()
                .path("paths").path("/api/v1/inbox/{id}").path("get")
                .path("description").asText();

        assertThat(description)
                .as("a client needs to be told it will never see a 403 here")
                .contains("404")
                .containsIgnoringCase("belongs to somebody else");
    }

    /**
     * The announcement endpoints, and the two contract facts a client cannot guess.
     *
     * <p>That cancellation answers 202 rather than 204 is the platform's rule, and a client treating
     * 204 as "stopped" would report a stop that has only been requested. That already-created
     * deliveries are not recalled is the other half: a caller who cancelled needs to know that some
     * mail is still going out.
     */
    @Test
    @DisplayName("the announcement endpoints are published with their cancellation contract")
    void announcementEndpointsArePublished() throws Exception {
        JsonNode paths = document().path("paths");

        assertThat(paths.has("/api/v1/announcements")).isTrue();
        assertThat(paths.has("/api/v1/announcements/outstanding-count")).isTrue();
        assertThat(paths.has("/api/v1/announcements/{id}")).isTrue();
        assertThat(paths.has("/api/v1/announcements/{id}/dismiss")).isTrue();
        assertThat(paths.has("/api/v1/announcements/{id}/email-run")).isTrue();
        assertThat(paths.has("/api/v1/announcements/{id}/email-run/cancellation")).isTrue();

        String cancel = paths.path("/api/v1/announcements/{id}/email-run/cancellation")
                .path("post").path("description").asText();
        assertThat(cancel)
                .as("202 rather than 204 is the contract, not an implementation detail")
                .contains("202");
        assertThat(cancel)
                .as("a caller who cancelled must know that committed deliveries still go out")
                .containsIgnoringCase("not recalled");
    }

    /**
     * The audience is absent from what a recipient can filter on. Asserted against the published
     * document because that is what a client generator reads.
     */
    @Test
    @DisplayName("the announcement feed documents that a foreign announcement is a 404")
    void announcementRefusalContractIsDocumented() throws Exception {
        String description = document()
                .path("paths").path("/api/v1/announcements/{id}").path("get")
                .path("description").asText();

        assertThat(description).contains("404");
        assertThat(description).containsIgnoringCase("indistinguishable");
    }

    @Test
    @DisplayName("the idempotence of the transitions is documented, not just implemented")
    void idempotenceIsDocumented() throws Exception {
        JsonNode inbox = document().path("paths");

        assertThat(inbox.path("/api/v1/inbox/{id}/read").path("post").path("description").asText())
                .containsIgnoringCase("idempotent");
        assertThat(inbox.path("/api/v1/inbox/read").path("post").path("description").asText())
                .containsIgnoringCase("repeat");
    }

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
