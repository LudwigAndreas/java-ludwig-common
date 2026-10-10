package ru.ludwigandreas.example.catalog.integration;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * This service describes itself to a user interface at {@code /server/info}, without credentials.
 *
 * <p>The endpoint and its allow-list belong to the observability starter and are tested there. Two
 * things are this service's own, and both live in the {@code ludwig.security.public-paths} block of
 * {@code application.yml}:
 *
 * <ul>
 *   <li>the decision to publish the path, which an edit to that list would undo silently;</li>
 *   <li>the three actuator entries restated beside it. A list in YAML replaces the security
 *       starter's default instead of extending it, so a block naming only the new path would put the
 *       health probes behind authentication - and nothing would say so until an orchestrator
 *       started restarting healthy pods.</li>
 * </ul>
 *
 * <p>Both are asserted through the real security filter chain. The path is written out rather than
 * taken from the starter's constant: a client knows it only as text.
 */
@AutoConfigureMockMvc
@SpringBootTest
@Import({TestSecurityConfiguration.class, PostgresContainerConfiguration.class})
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        // No broker in this test, as in the other integration tests here; the same property set
        // also keeps this class on their application context instead of starting another.
        "ludwig.identity.kafka.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=false"
})
class ServerInfoIT {

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

        assertThat(body.get("service").asText()).isEqualTo("product-catalog");
        assertThat(members).isSubsetOf("service", "version", "environment", "commit", "built");
    }

    @Test
    @DisplayName("restating the public paths did not put the health probes behind authentication")
    void theHealthEndpointIsStillPublic() throws Exception {
        int health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();

        // Not asserted as 200: whether the service is healthy in a test context is a different
        // question from whether an anonymous caller is allowed to ask.
        assertThat(health).isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }
}
