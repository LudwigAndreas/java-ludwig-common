package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * This service describes itself to a user interface at {@code /server/info}, without credentials.
 *
 * <p>The endpoint and its allow-list belong to the observability starter and are tested there. What
 * is this service's own is the decision to publish it - the entry in
 * {@code ludwig.security.public-paths} - and that is what would be lost silently if the list were
 * edited, so it is asserted here through the real security filter chain. The path is written out
 * rather than taken from the starter's constant: a client knows it only as text.
 */
@AutoConfigureMockMvc
class ServerInfoIT extends NotificationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("an anonymous caller is told which service answered, and nothing outside the allow-list")
    void describesItselfToAnAnonymousCaller() throws Exception {
        String json = mockMvc.perform(get("/server/info"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(json);
        List<String> members = new ArrayList<>();
        body.fieldNames().forEachRemaining(members::add);

        assertThat(body.get("service").asText()).isEqualTo("notification-service");
        assertThat(members).isSubsetOf("service", "version", "environment", "commit", "built");
    }
}
