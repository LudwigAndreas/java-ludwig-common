package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a UI sees when it asks this service which operations a caller holds.
 *
 * <p>The roles are this service's own ({@code ludwig.security.roles} in {@code application.yml}); the descriptions
 * come from each starter's bundle and this service's own. A description that equals its operation id means a bundle
 * key is missing and the UI would show {@code outbox.message.discard} to a person - startup refuses that, and this
 * asserts it end to end with every starter this service actually runs.
 */
@AutoConfigureMockMvc
class AdminDiscoveryIT extends NotificationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("the notification administrator holds operations, each with a description rather than its id")
    void everyHeldOperationIsDescribed() throws Exception {
        String json = mockMvc.perform(get("/me/operations").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode held = objectMapper.readTree(json);
        assertThat(held).isNotEmpty();
        held.forEach(operation -> assertThat(operation.path("description").asText())
                .as("description of %s", operation.path("id").asText())
                .isNotBlank()
                .isNotEqualTo(operation.path("id").asText()));
    }

    @Test
    @DisplayName("support holds the reads, its own tenant's deliveries, and not the discard")
    void supportSeesItsScopes() throws Exception {
        String json = mockMvc.perform(get("/me/operations").with(TestPrincipals.support("tenant-a")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, String> scopes = new HashMap<>();
        objectMapper.readTree(json).forEach(operation ->
                scopes.put(operation.path("id").asText(), operation.path("scope").toString()));
        assertThat(scopes).containsEntry("outbox.message.read", "[\"ALL\"]")
                .containsEntry("notification.delivery.read", "[\"TENANT\"]")
                .doesNotContainKey("outbox.message.discard")
                .doesNotContainKey("notification.delivery.retry");
    }

    @Test
    @DisplayName("the former administrative discovery path is gone")
    void formerPathIsUnmapped() throws Exception {
        mockMvc.perform(get("/admin/v1/me/operations").with(TestPrincipals.admin())).andExpect(status().isNotFound());
    }
}
