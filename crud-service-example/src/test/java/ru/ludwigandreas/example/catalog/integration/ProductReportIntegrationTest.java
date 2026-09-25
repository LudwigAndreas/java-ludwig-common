package ru.ludwigandreas.example.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ru.ludwigandreas.example.catalog.integration.TestPrincipals.admin;
import static ru.ludwigandreas.example.catalog.integration.TestPrincipals.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.example.catalog.repository.CategoryRepository;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.entity.ProductStatus;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;

/**
 * The catalogue export, end to end: HTTP request, keyset-paginated query against a real PostgreSQL,
 * enrichment over real HTTP against a stub directory, a file written to a sink, and the file read back.
 *
 * <h2>What this test is for</h2>
 *
 * <p>It is the demonstration that the export module is consumable. Everything it asserts is a claim the
 * module's README makes and that nothing else in this repository proves against a running service:
 *
 * <ul>
 *   <li>a run small enough to be synchronous returns the file from the same call;</li>
 *   <li>a column restricted to a role is <em>absent from the file</em> for a caller without it, rather
 *       than present and blank - the distinction §11 of the design turns on;</li>
 *   <li>a supplier the directory does not know produces a localized marker, not a blank cell, and does
 *       not drop the product;</li>
 *   <li>a partner that is down degrades the report visibly - a header, a run field, and marked cells -
 *       rather than failing the whole export or producing a file that looks complete;</li>
 *   <li>enrichment is batched: the number of calls tracks windows, not rows;</li>
 *   <li>an XLSX cell is typed, so a spreadsheet sums the price column rather than concatenating it.</li>
 * </ul>
 *
 * <h2>Why the row count is small</h2>
 *
 * <p>Thirty products, because this test is about the wiring being right. Whether the engine holds its
 * memory budget at a million rows is a different question with a different instrument - the tagged load
 * test in the export module, which is excluded from this build and reports measured numbers.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
@Import(TestSecurityConfiguration.class)
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        "ludwig.identity.kafka.enabled=false",
        // Every run in this test is below the synchronous threshold, so the poller has nothing to do
        // and leaving it on would only add a background thread claiming rows the test already has.
        "ludwig.export.poller.enabled=false",
        // The stub directory speaks plain HTTP. Minting a client-credentials token would need an
        // authorization server, and what is under test here is the enrichment path rather than the
        // rest-client starter's OAuth2 provider, which has its own tests.
        "ludwig.rest-client.clients.suppliers.auth.type=none"
})
class ProductReportIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    /** From the reference-data migration (0002-catalog-reference-data.xml). */
    private static final UUID TOOLS = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String REPORT_KEY = "catalog.products";
    private static final String RUNS_PATH = "/api/v1/reports/{key}/runs";
    private static final String DOWNLOAD_PATH = "/api/v1/reports/runs/{runId}/outputs/{format}";

    private static final int PRODUCT_COUNT = 30;
    private static final String SUPPLIER_COST_HEADER = "Supplier cost";
    private static final String UNKNOWN_SUPPLIER_MARKER = "Supplier not in directory";

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    private static SupplierDirectoryStub directory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository products;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private SecurityUserRepository identities;

    @BeforeAll
    static void startDirectory() {
        directory = SupplierDirectoryStub.startHealthy();
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    /**
     * Points the {@code suppliers} client at the stub.
     *
     * <p>A {@link DynamicPropertySource} rather than a fixed port because a fixed port is how a test
     * suite becomes order-dependent on whatever else is listening.
     *
     * <p>The sink is left alone deliberately: the module's default directory is created if absent, so
     * this test exercises the same path a service gets before anybody configures one.
     */
    @DynamicPropertySource
    static void wireStub(DynamicPropertyRegistry registry) {
        registry.add("ludwig.rest-client.clients.suppliers.base-url", directory::baseUrl);
    }

    @BeforeEach
    void seed() {
        directory.healIt();
        directory.resetRequests();
        // Deleted first, not upserted: SecurityUserEntity is an ExternalEntity keyed by the platform
        // user id, so save() on an id that already exists is an insert and fails the second time.
        identities.deleteAll();
        identities.saveAll(List.of(
                identity(TestPrincipals.ADMIN_SUBJECT, "ROLE_CATALOG_ADMIN"),
                identity(TestPrincipals.EDITOR_SUBJECT, "ROLE_CATALOG_EDITOR")));
        products.deleteAll();
        products.saveAll(catalogue());
        products.flush();
    }

    /**
     * A row in the local projection of the identity provider's user stream.
     *
     * <p>Seeded rather than taken from the injected {@code LudwigPrincipal}, and that is the point
     * rather than a workaround. A report's authorities are re-resolved when the run <em>executes</em>,
     * from this projection - not read from the token that requested it - because a deferred run can
     * execute hours after the request, on a different instance, with the requester's access revoked in
     * between. So a caller whose roles exist only in a token sees no restricted columns, which is what
     * these tests would show if this seeding were removed.
     *
     * @param subject the platform user id, which is also the primary key of the projection
     * @param role    the role the identity provider granted them
     * @return the projected user
     */
    private static SecurityUserEntity identity(String subject, String role) {
        SecurityUserEntity user = new SecurityUserEntity();
        user.setId(subject);
        user.setDisplayName(subject);
        user.setRoles(java.util.Set.of(role));
        // Not decoration: every projected row records which upstream produced it, so an operator
        // looking at an unexpected grant can tell whether it came from the user stream or from a test
        // fixture. The column is NOT NULL for exactly that reason.
        user.setSourceSystem("integration-test");
        return user;
    }

    /**
     * Thirty products across three suppliers, one of which the directory does not know.
     *
     * <p>{@code updatedAt} is not set here: it is filled by db-core's auditing, which is what the
     * report's window is over. Setting it would be testing a value the test wrote rather than the one
     * the application writes.
     */
    private List<ProductEntity> catalogue() {
        String[] suppliers = {SupplierDirectoryStub.SUPPLIER_ACME, SupplierDirectoryStub.SUPPLIER_GLOBEX,
                SupplierDirectoryStub.SUPPLIER_RETIRED};
        List<ProductEntity> batch = new ArrayList<>();
        for (int i = 0; i < PRODUCT_COUNT; i++) {
            ProductEntity product = new ProductEntity();
            product.setSku(String.format("EXPORT-%03d", i));
            product.setName("Exportable item " + i);
            product.setPrice(new BigDecimal("10.00").add(BigDecimal.valueOf(i)));
            product.setSupplierCost(new BigDecimal("5.00").add(BigDecimal.valueOf(i)));
            product.setStatus(ProductStatus.ACTIVE);
            product.setStockQuantity(i);
            product.setCategory(categories.getReferenceById(TOOLS));
            product.setSupplierPartnerId(suppliers[i % suppliers.length]);
            batch.add(product);
        }
        return batch;
    }

    @Test
    @DisplayName("a small run comes back from the same call, enriched, with the unknown supplier marked")
    void producesAnEnrichedCsvSynchronously() throws Exception {
        Map<String, Object> run = requestRun(admin(), "csv", status().isOk());

        assertThat(run).containsEntry("status", "SUCCEEDED");
        assertThat(run).containsEntry("rowsWritten", PRODUCT_COUNT);
        assertThat(run.get("degradedStages")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();

        List<String> lines = downloadCsv(admin(), (String) run.get("id"));
        assertThat(lines).hasSize(PRODUCT_COUNT + 1);

        // Every column the definition declares, in declaration order, for a caller who may see them all.
        assertThat(lines.get(0)).isEqualTo("SKU,Name,Category,Status,Price," + SUPPLIER_COST_HEADER
                + ",In stock,Supplier,Supplier rating,On-time rate,Last changed");

        // The default sort is ascending SKU, so row one is EXPORT-000, whose supplier is acme.
        assertThat(lines.get(1)).startsWith("EXPORT-000,Exportable item 0,TOOLS,ACTIVE,");
        assertThat(lines.get(1)).contains("ACME GmbH");

        // A supplier the directory retired: a marker in the enriched cells, and the product still here.
        assertThat(lines).anyMatch(line -> line.startsWith("EXPORT-002,")
                && line.contains(UNKNOWN_SUPPLIER_MARKER));
        assertThat(lines).allMatch(line -> !line.isBlank());
    }

    @Test
    @DisplayName("a caller without the role gets a file with no supplier-cost column at all")
    void hidesRestrictedColumnsByOmittingThem() throws Exception {
        Map<String, Object> run = requestRun(editor(), "csv", status().isOk());

        List<String> lines = downloadCsv(editor(), (String) run.get("id"));

        // Absent, not blank. A blank column would say these products have no purchase price.
        assertThat(lines.get(0)).doesNotContain(SUPPLIER_COST_HEADER);
        assertThat(lines.get(0)).isEqualTo(
                "SKU,Name,Category,Status,Price,In stock,Supplier,Supplier rating,On-time rate,Last changed");
        // The data rows are not counted by splitting on the separator: the localized datetime in the
        // last column contains one, correctly quoted, and a test that split naively would be asserting
        // its own parser rather than the file.
        assertThat(lines).hasSize(PRODUCT_COUNT + 1);
        assertThat(lines.get(1)).startsWith("EXPORT-000,Exportable item 0,TOOLS,ACTIVE,");
        assertThat(lines.get(1)).contains("ACME GmbH");
    }

    @Test
    @DisplayName("enrichment is batched: one lookup for thirty products, not thirty")
    void batchesPartnerLookups() throws Exception {
        requestRun(admin(), "csv", status().isOk());

        // Three distinct supplier ids across thirty rows, in one window, in one call. The assertion is
        // deliberately about the shape rather than the exact number: what must never happen is a call
        // per row.
        assertThat(directory.lookups()).hasSize(1);
        assertThat(directory.lookups().get(0).getUrl())
                .contains("id=" + SupplierDirectoryStub.SUPPLIER_ACME);
    }

    @Test
    @DisplayName("a directory that is down degrades the report visibly instead of failing it")
    void degradesWhenThePartnerIsDown() throws Exception {
        directory.breakIt();

        Map<String, Object> run = requestRun(admin(), "csv", status().isOk());

        assertThat(run).containsEntry("status", "SUCCEEDED");
        assertThat(run).containsEntry("degraded", true);
        assertThat(run.get("degradedStages")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).contains("supplier");

        MvcResult download = mockMvc.perform(get(DOWNLOAD_PATH, run.get("id"), "csv").with(admin()))
                .andExpect(status().isOk())
                // The header exists so that an automated consumer can refuse the file without parsing
                // it. A blank cell is never the only signal that a report is incomplete.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Report-Degraded", "true"))
                .andReturn();

        List<String> lines = linesOf(download);
        assertThat(lines).hasSize(PRODUCT_COUNT + 1);
        // Still every column and every product: the base data is intact and says so.
        assertThat(lines.get(0)).contains("Supplier");
    }

    @Test
    @DisplayName("the workbook's cells are typed, so a spreadsheet can sum them")
    void writesTypedXlsxCells() throws Exception {
        Map<String, Object> run = requestRun(admin(), "xlsx", status().isOk());

        MvcResult download = mockMvc.perform(get(DOWNLOAD_PATH, run.get("id"), "xlsx").with(admin()))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook workbook =
                     new XSSFWorkbook(new ByteArrayInputStream(download.getResponse().getContentAsByteArray()))) {
            Sheet data = workbook.getSheetAt(0);
            Row header = data.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("SKU");

            Row first = data.getRow(1);
            assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            // Price, supplier cost and stock are numbers in the file, not text that looks like numbers.
            // This is the assertion that fails when a writer reaches for toString().
            assertThat(first.getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(4).getNumericCellValue()).isEqualTo(10.0d);
            assertThat(first.getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(6).getCellType()).isEqualTo(CellType.NUMERIC);
            // The date column is a number carrying a date format, which is how a spreadsheet stores one.
            assertThat(first.getCell(10).getCellType()).isEqualTo(CellType.NUMERIC);

            // The totals row: XLSX declares it can carry one, so the engine folds the aggregates into it.
            Row totals = data.getRow(PRODUCT_COUNT + 1);
            assertThat(totals).as("a totals row after %d data rows", PRODUCT_COUNT).isNotNull();
            double expectedStock = PRODUCT_COUNT * (PRODUCT_COUNT - 1) / 2.0d;
            assertThat(totals.getCell(6).getNumericCellValue()).isEqualTo(expectedStock);

            // The metadata sheet, which is what makes a file produced months ago explainable. Its keys
            // are the localized labels rather than message keys, because the sheet is for a reader.
            Sheet metadata = workbook.getSheet(workbook.getSheetName(workbook.getNumberOfSheets() - 1));
            assertThat(metadataValues(metadata))
                    .containsEntry("Report", "Product catalogue")
                    .containsEntry("Report version", "1")
                    .containsEntry("Requested by", TestPrincipals.ADMIN_SUBJECT)
                    .containsKey("Parameters");
        }
    }

    /** Every key/value pair on the metadata sheet, so a test can assert on one without its row index. */
    private static Map<String, String> metadataValues(Sheet metadata) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Row row : metadata) {
            Cell key = row.getCell(0);
            Cell value = row.getCell(1);
            if (key != null && key.getCellType() == CellType.STRING) {
                values.put(key.getStringCellValue(), value == null ? "" : value.toString());
            }
        }
        return values;
    }

    /**
     * Requests the report and returns the run, parsed.
     *
     * <p>The window is wide on both sides of now because auditing set {@code updatedAt} to the moment
     * the seed committed, and a window derived from the clock the test reads would be a race.
     */
    private Map<String, Object> requestRun(RequestPostProcessor caller, String format,
                                           org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        Instant now = Instant.now();
        Map<String, Object> body = Map.of(
                "parameters", Map.of(
                        "changedFrom", now.minus(1, ChronoUnit.DAYS).toString(),
                        "changedUntil", now.plus(1, ChronoUnit.DAYS).toString()),
                "formats", List.of(format),
                // Named rather than inherited from Accept-Language, so the expected header text above is
                // a property of the request instead of a property of the machine running the test.
                "locale", "en",
                "timeZone", "UTC");
        MvcResult result = mockMvc.perform(post(RUNS_PATH, REPORT_KEY)
                        .with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(expected)
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
    }

    /**
     * Downloads a run's CSV as the caller who requested it.
     *
     * <p>The caller matters: the download endpoint answers 404 - not 403 - for a run belonging to
     * somebody else, deliberately, because 403 on a run id would confirm that the run exists. So a
     * test that downloaded everything as the administrator would silently stop testing what the
     * narrower caller actually receives.
     */
    private List<String> downloadCsv(RequestPostProcessor caller, String runId) throws Exception {
        MvcResult result = mockMvc.perform(get(DOWNLOAD_PATH, runId, "csv").with(caller))
                .andExpect(status().isOk())
                .andReturn();
        return linesOf(result);
    }

    /**
     * The CSV's lines, with the byte-order mark stripped.
     *
     * <p>The BOM is deliberate - it is what makes Excel read a UTF-8 file as UTF-8 rather than as the
     * machine's ANSI code page - so the test strips it rather than asserting it is absent.
     */
    private static List<String> linesOf(MvcResult result) throws Exception {
        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return List.of(text.split("\r\n"));
    }
}
