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
import ru.ludwigandreas.testsupport.docs.PublishedDocument;

/**
 * Publishes this service's HTTP surface to {@code docs/api/} and fails the build when the committed
 * copy no longer matches what the service actually serves.
 *
 * <p>The point is not documentation for its own sake. A service's API exists only inside a running
 * process, so a pull request that widens a response, drops a field or changes a status code shows
 * nothing a reviewer can see. Generating the document from the real context and committing it turns
 * every such change into a diff.
 *
 * <p>Three properties make this a check rather than a chore, and all three are deliberate:
 *
 * <ul>
 *   <li><b>The document is canonicalized before comparison</b> - every object's members sorted, the
 *       {@code servers} block removed. springdoc builds the document by walking handler mappings and
 *       nothing promises that walk is in the same order on another JVM or after a rename. An
 *       unsorted document would produce diffs that are pure reordering, and the first time that
 *       happens somebody refreshes without reading the diff, at which point the check is decorative.
 *       Member order carries no meaning in OpenAPI; array order does, so arrays are left alone.</li>
 *   <li><b>An ordinary build never rewrites the committed file.</b> A test that dirties a tracked
 *       file as a side effect makes {@code git status} useless and lets a stale document be committed
 *       unseen. Refreshing is opt-in through {@code -Dludwig.apidocs.write=true}, so the diff is
 *       always something the author asked for.</li>
 *   <li><b>A hand edit fails exactly as drift does</b>, because the two are indistinguishable from
 *       here and are wrong for the same reason.</li>
 * </ul>
 *
 * <p>The fetch-independent part - canonicalizing, writing and comparing - is {@link PublishedDocument}'s,
 * shared with {@code crud-service-example} and with each service's {@code AsyncApiDocumentIT}.
 */
@AutoConfigureMockMvc
class ApiDocumentIT extends NotificationTestBase {

    private static final String ARTIFACT_ID = "notification-service";

    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("the committed OpenAPI document matches what this service serves")
    void committedDocumentIsCurrent() throws Exception {
        PublishedDocument.check(ARTIFACT_ID, PublishedDocument.Kind.OPENAPI, document(), ApiDocumentIT.class);
    }

    /**
     * The error contract, which no controller return type mentions.
     *
     * <p>Every error this platform serves is produced by {@code web-core}'s single advice rather than
     * returned from a handler, so a document inferred only from return types describes the happy paths
     * alone and a generated client has no type able to read a failure. Asserted against the published
     * document because that is what a client generator reads.
     */
    @Test
    @DisplayName("the published document declares the problem schema and names the stable member")
    void problemSchemaIsPublished() throws Exception {
        JsonNode schema = document().path("components").path("schemas").path(PROBLEM_SCHEMA);

        assertThat(schema.isMissingNode())
                .as("without this, a generated client cannot read any error this service produces")
                .isFalse();

        String code = schema.path("properties").path("code").path("description").asText();
        assertThat(code)
                .as("a consumer must be told which member is safe to branch on")
                .containsIgnoringCase("stable");

        String detail = schema.path("properties").path("detail").path("description").asText();
        assertThat(detail)
                .as("detail is localized, so branching on it breaks under a different Accept-Language")
                .containsIgnoringCase("localized");
    }

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

}
