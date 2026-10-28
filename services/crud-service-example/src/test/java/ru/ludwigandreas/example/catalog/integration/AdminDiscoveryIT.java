package ru.ludwigandreas.example.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * What an administrative UI sees when it asks this service which operations its administrator holds.
 *
 * <p>The grants are this service's own ({@code ludwig.admin.grants} in {@code application.yml}); the descriptions
 * come from each starter's bundle. A description that equals its operation id means a bundle key is missing and the
 * UI would show {@code outbox.privileged} to a person - startup refuses that, and this asserts it end to end with
 * every starter this service actually runs.
 */
@AutoConfigureMockMvc
@SpringBootTest
@Import({TestSecurityConfiguration.class, PostgresContainerConfiguration.class})
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        // The same property set as the other integration tests here, so this class shares their context.
        "ludwig.identity.kafka.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=false"
})
class AdminDiscoveryIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("the catalog administrator holds operations, each with a description rather than its id")
    void everyHeldOperationIsDescribed() throws Exception {
        String json = mockMvc.perform(get("/admin/v1/me/operations").with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode held = objectMapper.readTree(json);
        assertThat(held).isNotEmpty();
        held.forEach(operation -> assertThat(operation.path("description").asText())
                .as("description of %s", operation.path("id").asText())
                .isNotBlank()
                .isNotEqualTo(operation.path("id").asText()));
    }
}
