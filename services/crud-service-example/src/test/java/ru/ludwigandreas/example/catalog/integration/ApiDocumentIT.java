package ru.ludwigandreas.example.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * Publishes this service's HTTP surface to {@code docs/api/} and fails the build when the committed
 * copy no longer matches what the service actually serves.
 *
 * <p>The point is not documentation for its own sake. A service's API exists only inside a running
 * process, so a pull request that widens a response, drops a field or changes a status code shows
 * nothing a reviewer can see. Generating the document from the real context and committing it turns
 * every such change into a diff. The canonicalization, the opt-in refresh and the reason a hand edit
 * fails exactly as drift does are all explained in {@code notification-service}'s copy of this test.
 *
 * <p>This service carries one assertion the other does not: that the product search endpoint's five
 * OData query options appear in the published document. That property is already required by the
 * {@code odata-query-contract} capability, and until this service had springdoc at all it was
 * unverifiable here - which mattered more than anywhere else, because this is the module a new
 * service is copied from. The parameters are contributed by a customizer in
 * {@code odata-filter-spring-boot-starter} that activates on springdoc's presence, so the assertion
 * also fails if that starter ever stops reaching this service's classpath.
 *
 * <p>The context annotations deliberately repeat {@code CatalogIntegrationTest}'s verbatim. Every
 * distinct property set builds its own Spring context and each context starts its own PostgreSQL
 * container; matching them keeps this suite on one container rather than adding a second.
 */
@AutoConfigureMockMvc
@SpringBootTest
@Import({TestSecurityConfiguration.class, PostgresContainerConfiguration.class})
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        // No broker in this test: the projection tables are exercised through injected principals
        // rather than through the OIDC user stream.
        "ludwig.identity.kafka.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=false"
})
class ApiDocumentIT {

    /** Set to refresh the committed document. Deliberately not the default; see the class comment. */
    private static final String WRITE_PROPERTY = "ludwig.apidocs.write";

    private static final String ARTIFACT_ID = "crud-service-example";

    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("the committed OpenAPI document matches what this service serves")
    void committedDocumentIsCurrent() throws Exception {
        String generated = canonicalDocument();

        Path target = Paths.get("target", "openapi", ARTIFACT_ID + ".openapi.yaml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, generated, StandardCharsets.UTF_8);

        Path committed = repositoryRoot().resolve("docs").resolve("api")
                .resolve(ARTIFACT_ID + ".openapi.yaml");

        if (Boolean.parseBoolean(System.getProperty(WRITE_PROPERTY))) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, generated, StandardCharsets.UTF_8);
            return;
        }

        if (!Files.exists(committed)) {
            fail("the committed copy is missing: %s%nGenerate it with:%n  %s"
                    .formatted(committed, refreshCommand()));
        }

        String current = Files.readString(committed, StandardCharsets.UTF_8);
        assertThat(current)
                .as("this service's HTTP surface has changed but %s was not refreshed.%n"
                        + "Refresh it with:%n  %s%nthen read the diff before committing it.",
                        committed, refreshCommand())
                .isEqualTo(generated);
    }

    /**
     * The error contract, which no controller return type mentions.
     *
     * <p>Every error this platform serves is produced by {@code web-core}'s single advice rather than
     * returned from a handler, so a document inferred only from return types describes the happy paths
     * alone and a generated client has no type able to read a failure.
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

    /**
     * The five query options, asserted against the published document because that is what a client
     * generator reads.
     *
     * <p>The options are bound by a custom argument resolver, and a parameter filled in by one is
     * invisible to springdoc - so without the starter's customizer the search endpoint would document
     * no query parameters at all while happily accepting five. That is the defect this asserts is not
     * present.
     */
    @Test
    @DisplayName("the product search endpoint publishes the five OData query options")
    void odataQueryOptionsArePublished() throws Exception {
        JsonNode parameters = document()
                .path("paths").path("/api/v1/products").path("get").path("parameters");

        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.path("name").asText()));

        assertThat(names)
                .as("required by the odata-query-contract capability; contributed by "
                        + "odata-filter-spring-boot-starter's customizer, which needs springdoc on "
                        + "this service's classpath to load at all")
                .contains("$filter", "$orderby", "$top", "$skip", "$count");

        parameters.forEach(parameter -> {
            String name = parameter.path("name").asText();
            if (name.startsWith("$")) {
                assertThat(parameter.path("schema").path("type").asText())
                        .as("%s is published without a type, so a generated client cannot type it", name)
                        .isNotEmpty();
            }
        });
    }

    private String refreshCommand() {
        return "mvn -pl :%s verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false -D%s=true"
                .formatted(ARTIFACT_ID, WRITE_PROPERTY);
    }

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** The generated document, canonicalized exactly as the committed copy is written. */
    private String canonicalDocument() throws Exception {
        JsonNode root = document();
        Object sorted = sortedTree(root);
        if (sorted instanceof Map<?, ?> map) {
            // Derived from the request the document was fetched with, so it describes this test
            // harness rather than any deployment. Committing "http://localhost" would be a statement
            // about where the service runs, which this document is in no position to make.
            map.remove("servers");
        }
        return yaml().dump(sorted);
    }

    private Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        // Long descriptions are emitted on one line rather than folded. Folding is width-dependent,
        // and a width is one more thing that has to agree across machines for the comparison to hold.
        options.setSplitLines(false);
        return new Yaml(options);
    }

    /**
     * The document as plain collections, with every object's members in key order.
     *
     * <p>{@link TreeMap} is what produces the canonical form. Arrays keep their order because in
     * OpenAPI an array's order is meaningful - {@code required}, {@code enum} and {@code parameters}
     * all mean something different reordered - while an object's is not.
     */
    private Object sortedTree(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), sortedTree(field.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            node.forEach(child -> values.add(sortedTree(child)));
            return values;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    /**
     * The repository root, found by walking up for the two directories that only the root has.
     *
     * <p>Surefire and failsafe run with the module directory as the working directory, so the path to
     * {@code docs/} is relative to a location this class should not hard-code: a module that moves one
     * level would silently write its document somewhere else. Both {@code docs/} and {@code openspec/}
     * exist only at the root, so requiring both is unambiguous.
     */
    private Path repositoryRoot() throws IOException {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs"))
                    && Files.isDirectory(candidate.resolve("openspec"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("no repository root above " + Paths.get("").toAbsolutePath()
                + " holds both docs/ and openspec/");
    }
}
