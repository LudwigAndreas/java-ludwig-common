package ru.ludwigandreas.example.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.service.event.ProductEventType;
import ru.ludwigandreas.example.catalog.web.dto.CreateProductRequest;
import ru.ludwigandreas.example.catalog.web.dto.ProductResponse;
import ru.ludwigandreas.example.catalog.web.dto.ProductStatusDto;
import ru.ludwigandreas.example.catalog.web.dto.UpdateProductRequest;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

/**
 * End-to-end pass over the whole stack against a real PostgreSQL: HTTP -> controller -> service ->
 * QueryDSL -> Postgres, with the real Liquibase migrations applied and Hibernate validating its
 * mappings against them at startup.
 *
 * <p>The outbox poller is switched off: this test asserts that the event <em>row</em> is written in
 * the same transaction as the product, which is the guarantee the outbox pattern provides. Actually
 * dispatching it to Kafka is the outbox module's own integration test's job.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
@Import(TestSecurityConfiguration.class)
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        // No broker in this test: the projection tables are exercised through injected principals
        // rather than through the OIDC user stream.
        "ludwig.identity.kafka.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=false"
})
class CatalogIntegrationTest {

    /**
     * Pinned by name, version <em>and</em> digest: a tag alone can be re-pointed at different
     * content, so tests (and everything else that runs a container) would silently change what they
     * execute.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    /** Category ids come from the reference-data migration (0002-catalog-reference-data.xml). */
    private static final UUID TOOLS = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ELECTRONICS = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID UNKNOWN_CATEGORY = UUID.fromString("99999999-9999-9999-9999-999999999999");

    // The connection name is given explicitly because Spring Boot otherwise deduces it by parsing
    // the image name, and a name carrying both a tag and a digest is not parseable as a repository.
    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @BeforeEach
    void resetState() {
        productRepository.deleteAll();
        outboxMessageRepository.deleteAll();
    }

    @Test
    void createsReadsUpdatesAndDeletesAProduct() throws Exception {
        ProductResponse created = create(request("HAMMER-1", "Hammer", "19.99", TOOLS));

        assertThat(created.id()).isNotNull();
        assertThat(created.version()).isZero();
        assertThat(created.category().code()).isEqualTo("TOOLS");

        mockMvc.perform(get("/api/v1/products/{id}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("HAMMER-1"))
                .andExpect(jsonPath("$.stockQuantity").value(10))
                // Internal cost never reaches the API, whatever the client asks for.
                .andExpect(jsonPath("$.supplierCost").doesNotExist());

        UpdateProductRequest update = new UpdateProductRequest("Hammer Pro", "A better hammer",
                new BigDecimal("42.50"), new BigDecimal("21.00"), ProductStatusDto.DISCONTINUED,
                3, ELECTRONICS, created.version());

        mockMvc.perform(put("/api/v1/products/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hammer Pro"))
                .andExpect(jsonPath("$.status").value("DISCONTINUED"))
                .andExpect(jsonPath("$.category.code").value("ELECTRONICS"))
                .andExpect(jsonPath("$.version").value(1))
                // The SKU is immutable, and the update payload has no way to express it.
                .andExpect(jsonPath("$.sku").value("HAMMER-1"));

        mockMvc.perform(delete("/api/v1/products/{id}", created.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/products/{id}", created.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void writesAuditColumnsAndAnOutboxEventForEveryChange() throws Exception {
        ProductResponse created = create(request("HAMMER-2", "Hammer", "19.99", TOOLS));

        ProductEntity stored = productRepository.getByIdOrThrow(created.id());
        // db-core's auditing takes the auditor from the Spring Security context, which this module
        // fills with the LudwigPrincipal's subject - the same value an OWN data scope compares
        // against, which is why owner scoping needs no column of its own.
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getCreatedBy()).isEqualTo(TestPrincipals.ADMIN_SUBJECT);

        List<OutboxMessage> messages = outboxMessageRepository.findAll();
        assertThat(messages).hasSize(1);
        OutboxMessage message = messages.get(0);
        assertThat(message.getAggregateType()).isEqualTo("Product");
        assertThat(message.getAggregateId()).isEqualTo(created.id().toString());
        assertThat(message.getEventType()).isEqualTo(ProductEventType.CREATED);
        assertThat(message.getDestination()).isEqualTo("catalog.product-events");
        // The payload round-trips through a jsonb column, so it is compared as JSON, not as text.
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.path("sku").asText()).isEqualTo("HAMMER-2");
        assertThat(payload.path("categoryCode").asText()).isEqualTo("TOOLS");
    }

    @Test
    void rejectsADuplicateSku() throws Exception {
        create(request("HAMMER-3", "Hammer", "19.99", TOOLS));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request("HAMMER-3", "Another hammer", "9.99", TOOLS))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("error.product.sku-exists"))
                .andExpect(jsonPath("$.detail").value("A product with SKU HAMMER-3 already exists."));
    }

    /**
     * SKU matching ignores case in the query (and in the unique index behind it), so a differently
     * cased SKU can never become a second product - even though the API itself only accepts
     * upper-case SKUs today.
     */
    @Test
    void matchesSkusCaseInsensitively() throws Exception {
        ProductResponse created = create(request("HAMMER-6", "Hammer", "19.99", TOOLS));

        assertThat(productRepository.lookupBySku("hammer-6"))
                .get()
                .extracting(ProductEntity::getId)
                .isEqualTo(created.id());
        assertThat(productRepository.skuTaken("HaMmEr-6", null)).isTrue();
        assertThat(productRepository.skuTaken("HaMmEr-6", created.id())).isFalse();
    }

    @Test
    void rejectsAnUpdateBuiltOnAStaleVersion() throws Exception {
        ProductResponse created = create(request("HAMMER-4", "Hammer", "19.99", TOOLS));
        UpdateProductRequest stale = new UpdateProductRequest("Hammer Pro", null,
                new BigDecimal("42.50"), null, ProductStatusDto.ACTIVE, 1, TOOLS, 7L);

        mockMvc.perform(put("/api/v1/products/{id}", created.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("error.product.stale-version"));
    }

    @Test
    void rejectsAnUnknownCategoryAsUnprocessable() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request("HAMMER-5", "Hammer", "19.99", UNKNOWN_CATEGORY))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("error.category.not-found"));
    }

    @Test
    void searchesWithODataFilterOrderAndPaging() throws Exception {
        create(request("SAW-1", "Saw", "30.00", TOOLS));
        create(request("DRILL-1", "Drill", "120.00", TOOLS));
        create(request("PHONE-1", "Phone", "500.00", ELECTRONICS));

        mockMvc.perform(get("/api/v1/products")
                        .param("$filter", "price lt 200 and category/code eq 'TOOLS'")
                        .param("$orderby", "price desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].sku").value("DRILL-1"))
                .andExpect(jsonPath("$.content[1].sku").value("SAW-1"));

        mockMvc.perform(get("/api/v1/products")
                        .param("$filter", "contains(name, 'o')")
                        .param("$orderby", "name asc")
                        .param("$top", "1")
                        .param("$skip", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0]").doesNotExist());
    }

    @Test
    void refusesToFilterOnFieldsThePolicyDoesNotExpose() throws Exception {
        // Not annotated @Filterable at all: unreachable, however the query is phrased.
        mockMvc.perform(get("/api/v1/products").param("$filter", "contains(description, 'secret')"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.filter.UnfilterableFieldException"));

        // Annotated, but restricted to ROLE_CATALOG_ADMIN - and an editor does not hold it.
        mockMvc.perform(get("/api/v1/products").param("$filter", "supplierCost lt 5")
                        .with(TestPrincipals.editor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("error.filter.FilterAccessDeniedException"));
    }

    @Test
    void reportsValidationFailuresPerFieldInTheCallersLanguage() throws Exception {
        CreateProductRequest invalid = new CreateProductRequest("lower case sku", "", null,
                new BigDecimal("-1"), null, ProductStatusDto.ACTIVE, -5, TOOLS);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.validation"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.violations[?(@.field == 'name')].message")
                        .value("Name is required."))
                .andExpect(jsonPath("$.violations[?(@.field == 'stockQuantity')].message")
                        .value("Stock quantity must not be negative."));

        MvcResult russian = mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректный запрос"))
                .andReturn();

        assertThat(russian.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Наименование обязательно.");
    }

    @Test
    void localizesBusinessErrorsFromTheAcceptLanguageHeader() throws Exception {
        UUID missing = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/products/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.detail").value("No product exists with id " + missing + "."));

        mockMvc.perform(get("/api/v1/products/{id}", missing).header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Товар не найден"))
                .andExpect(jsonPath("$.detail")
                        .value("Товар с идентификатором " + missing + " не существует."));

        // An unsupported language falls back to the default bundle, not to the server's locale.
        mockMvc.perform(get("/api/v1/products/{id}", missing).header(HttpHeaders.ACCEPT_LANGUAGE, "fr"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void refusesAnUnauthenticatedRequestAsALocalizedProblem() throws Exception {
        mockMvc.perform(get("/api/v1/products").with(org.springframework.security.test.web.servlet
                        .request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ludwig.security.error.unauthorized"));
    }

    /**
     * The pre-filter half of data-level authorization: the partner's scope is part of the WHERE clause,
     * so the page it gets back and the total it is told are both computed over its own rows only.
     */
    @Test
    void aPartnerListsOnlyTheProductsFiledUnderItsOwnCode() throws Exception {
        create(request("PARTNER-1", "Partner hammer", "19.99", TOOLS), TestPrincipals.partner());
        create(request("CATALOG-1", "Catalog hammer", "29.99", TOOLS));

        mockMvc.perform(get("/api/v1/products").with(TestPrincipals.partner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("PARTNER-1"));

        // The admin's own view is unrestricted, so both rows are there - the difference is the caller,
        // not the data.
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /**
     * The post-check half: a direct load by id never passes through the scoped search query, so this is
     * the path that would otherwise hand any product to anyone able to guess an id.
     */
    @Test
    void aPartnerCannotReadAProductBelongingToTheCatalog() throws Exception {
        ProductResponse catalogOwned = create(request("CATALOG-2", "Catalog saw", "31.00", TOOLS));

        mockMvc.perform(get("/api/v1/products/{id}", catalogOwned.id()).with(TestPrincipals.partner()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("error.forbidden"));
    }

    /**
     * Read and write scopes differ for the same role: an editor reads the whole catalog
     * ({@code read: ALL}) but may only change what they created ({@code write: OWN}).
     */
    @Test
    void anEditorMayReadEveryProductButOnlyWriteItsOwn() throws Exception {
        ProductResponse mine = create(request("EDITOR-1", "Editor hammer", "19.99", TOOLS),
                TestPrincipals.editor());

        mockMvc.perform(get("/api/v1/products/{id}", mine.id()).with(TestPrincipals.otherEditor()))
                .andExpect(status().isOk());

        UpdateProductRequest update = new UpdateProductRequest("Hijacked", "not yours",
                new BigDecimal("1.00"), null, ProductStatusDto.ACTIVE, 1, TOOLS, mine.version());

        mockMvc.perform(put("/api/v1/products/{id}", mine.id())
                        .with(TestPrincipals.otherEditor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("error.forbidden"));

        assertThat(productRepository.getByIdOrThrow(mine.id()).getName()).isEqualTo("Editor hammer");
    }

    /**
     * The membership axis: a caller with no catalog-wide read role sees exactly the products they are
     * named on - in the list <em>and</em> on a direct load, which are enforced by two different halves of
     * the same binding.
     */
    @Test
    void aWatcherSeesOnlyTheProductsTheyAreNamedOn() throws Exception {
        ProductResponse watched = create(request("WATCH-1", "Watched hammer", "19.99", TOOLS));
        ProductResponse unwatched = create(request("WATCH-2", "Other hammer", "29.99", TOOLS));

        ProductEntity entity = productRepository.getByIdOrThrow(watched.id());
        entity.getWatcherSubjects().add(TestPrincipals.WATCHER_SUBJECT);
        productRepository.saveAndFlush(entity);

        // Pre-filtered in the query: one row, and a total that counts only that row.
        mockMvc.perform(get("/api/v1/products").with(TestPrincipals.watcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("WATCH-1"));

        // Post-checked on load: the same rule, on a path the query predicate never sees.
        mockMvc.perform(get("/api/v1/products/{id}", watched.id()).with(TestPrincipals.watcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("WATCH-1"));

        mockMvc.perform(get("/api/v1/products/{id}", unwatched.id()).with(TestPrincipals.watcher()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("error.forbidden"));
    }

    /** Deleting is admin-only, and that is a resource-level rule, so it is refused before any row is read. */
    @Test
    void anEditorCannotDeleteEvenItsOwnProduct() throws Exception {
        ProductResponse mine = create(request("EDITOR-2", "Editor saw", "19.99", TOOLS),
                TestPrincipals.editor());

        mockMvc.perform(delete("/api/v1/products/{id}", mine.id()).with(TestPrincipals.editor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("error.forbidden"));
    }

    private ProductResponse create(CreateProductRequest request) throws Exception {
        return create(request, TestPrincipals.admin());
    }

    private ProductResponse create(CreateProductRequest request, RequestPostProcessor caller)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), ProductResponse.class);
    }

    private CreateProductRequest request(String sku, String name, String price, UUID categoryId) {
        return new CreateProductRequest(sku, name, name + " description", new BigDecimal(price),
                new BigDecimal("5.00"), ProductStatusDto.ACTIVE, 10, categoryId);
    }
}
