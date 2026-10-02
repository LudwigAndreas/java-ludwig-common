package ru.ludwigandreas.example.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * The reference import, end to end: a real multipart request, a real workbook, real products.
 *
 * <h2>What this covers that the module's own tests cannot</h2>
 *
 * <p>The module's suite tests the engine against a recording handler. This tests the thing a service author
 * actually assembles: a `@FileAction` bean found by component scanning, an action resolved from this service's own
 * YAML, an authority checked against this service's own principals, and products appearing in
 * {@code catalog_product} through {@code ProductService}. If the wiring a consumer depends on is wrong, the
 * module's tests all pass and this one fails - which is the whole reason a starter ships a reference consumer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestSecurityConfiguration.class, PostgresContainerConfiguration.class})
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        "ludwig.identity.kafka.enabled=false",
        "ludwig.export.poller.enabled=false",
        "ludwig.rest-client.clients.suppliers.auth.type=none",
        // Pushed far out so a worker tick cannot race the assertions. The action is INLINE anyway, so the
        // deferred worker has nothing to claim - but retention would otherwise collect what these cases create.
        "ludwig.file-action.deferred.initial-delay=PT1H",
        "ludwig.file-action.storage.retention-interval=PT1H"
})
class ProductImportIntegrationTest {

    private static final String ACTION = "product-import";
    private static final String SUBMIT_PATH = "/api/v1/file-actions/" + ACTION;
    private static final String TEMPLATE_PATH = SUBMIT_PATH + "/template";

    /** A category from the reference-data migration (0002-catalog-reference-data.xml). */
    private static final String KNOWN_CATEGORY = "Tools";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository products;

    @org.junit.jupiter.api.BeforeAll
    static void createStorageRoot() throws IOException {
        // FilesystemObjectStore refuses a root that is not there, deliberately: a store silently creating its own
        // root would hide a misconfigured path in production. A test that wants one has to make it.
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("/tmp/ludwig-catalog"));
    }

    @BeforeEach
    void clearImportedProducts() {
        products.findAll().stream()
                .filter(product -> product.getSku().startsWith("IMP-"))
                .forEach(products::delete);
    }

    @Test
    @DisplayName("a clean sheet validates, and confirming it creates the products")
    void aCleanSheetImports() throws Exception {
        String id = idOf(submit(workbook(List.of(
                row("IMP-1", "Imported hammer", "19.99", 5, KNOWN_CATEGORY),
                row("IMP-2", "Imported mallet", "24.50", 3, KNOWN_CATEGORY))))
                // 202, not 200: a VALIDATED submission is not terminal, and the operation contract says a 200
                // from a submit endpoint must carry a terminal envelope. A CONFIRM-mode action therefore always
                // answers 202 on submit, with the status-resource header, and 200 on the confirm.
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Operation-Location"))
                .andExpect(jsonPath("$.state").value("VALIDATED"))
                .andExpect(jsonPath("$.rowsRead").value(2))
                .andExpect(jsonPath("$.rowsApplied").value(0))
                .andExpect(jsonPath("$.awaitingConfirmation").value(true))
                .andReturn());

        assertThat(products.lookupBySku("IMP-1"))
                .as("a CONFIRM-mode action that created products during validation would have no confirm step")
                .isEmpty();

        mockMvc.perform(post(SUBMIT_PATH + "/{id}/confirm", id).with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPLIED"))
                .andExpect(jsonPath("$.rowsApplied").value(2));

        assertThat(products.lookupBySku("IMP-1")).isPresent();
        assertThat(products.lookupBySku("IMP-2")).isPresent();
    }

    @Test
    @DisplayName("a row naming an unknown category is one reject and the others still import")
    void anUnknownCategoryIsOneReject() throws Exception {
        String id = idOf(submit(workbook(List.of(
                row("IMP-1", "Imported hammer", "19.99", 5, KNOWN_CATEGORY),
                row("IMP-2", "Imported mallet", "24.50", 3, "Nonexistent"),
                row("IMP-3", "Imported chisel", "9.99", 7, KNOWN_CATEGORY))))
                .andExpect(status().isAccepted())
                .andReturn());

        mockMvc.perform(post(SUBMIT_PATH + "/{id}/confirm", id).with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsApplied").value(2))
                .andExpect(jsonPath("$.rowsRejected").value(1));

        assertThat(products.lookupBySku("IMP-2")).isEmpty();
        assertThat(products.lookupBySku("IMP-3")).isPresent();
    }

    @Test
    @DisplayName("the reject names the row and the reason in a resource a client can page")
    void theRejectIsServedPaged() throws Exception {
        String id = idOf(submit(workbook(List.of(
                row("IMP-1", "Imported hammer", "19.99", 5, KNOWN_CATEGORY),
                row("IMP-2", "Imported mallet", "24.50", 3, "Nonexistent"))))
                .andReturn());
        mockMvc.perform(post(SUBMIT_PATH + "/{id}/confirm", id).with(TestPrincipals.admin()));

        mockMvc.perform(get(SUBMIT_PATH + "/{id}/rejects", id).with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].row").value(3))
                .andExpect(jsonPath("$.content[0].code").value("catalog.import.unknown-category"))
                .andExpect(jsonPath("$.content[0].message")
                        .value(org.hamcrest.Matchers.containsString("Nonexistent")));
    }

    @Test
    @DisplayName("a cell that is not a number is a cell-level reject naming the column")
    void aBadCellNamesTheColumn() throws Exception {
        String id = idOf(submit(workbookWithText("IMP-1", "Imported hammer", "not a price", "5",
                KNOWN_CATEGORY)).andReturn());

        mockMvc.perform(get(SUBMIT_PATH + "/{id}/rejects", id).with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].column").value("Price"))
                .andExpect(jsonPath("$.content[0].code").value("file-action.cell-not-coercible"));
    }

    @Test
    @DisplayName("a sheet missing a required column is refused as a file, with a ProblemDetail")
    void aMissingColumnIsAFileLevelProblem() throws Exception {
        byte[] content = workbookWithHeadings(List.of("SKU", "Name", "Price"),
                List.<Object[]>of(new Object[] {"IMP-1", "Imported hammer", 19.99d}));

        mockMvc.perform(multipart(SUBMIT_PATH)
                        .file(new MockMultipartFile("file", "products.xlsx",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("file-action.missing-column"))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Stock")));
    }

    @Test
    @DisplayName("a legacy .xls is refused with the message that tells the user what to do")
    void aLegacyWorkbookIsRefused() throws Exception {
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                       (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1, 0, 0, 0, 0};

        mockMvc.perform(multipart(SUBMIT_PATH)
                        .file(new MockMultipartFile("file", "products.xls",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, ole2))
                        .with(TestPrincipals.admin()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("file-action.legacy-xls"));
    }

    @Test
    @DisplayName("re-submitting the same sheet returns the first submission rather than importing twice")
    void resubmittingIsDeduplicated() throws Exception {
        byte[] content = workbook(List.<Object[]>of(row("IMP-1", "Imported hammer", "19.99", 5, KNOWN_CATEGORY)));

        String first = idOf(submitBytes(content).andReturn());
        String second = idOf(submitBytes(content).andReturn());

        assertThat(second)
                .as("a user double-clicking the drop zone is the normal case, not an edge one")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("the template endpoint serves a workbook whose headings the reader accepts")
    void theTemplateBinds() throws Exception {
        MvcResult template = mockMvc.perform(get(TEMPLATE_PATH).with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("product-import-template.xlsx")))
                .andReturn();

        assertThat(template.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    @DisplayName("a caller without the authority is refused on the submit and on the read")
    void theAuthorityIsEnforced() throws Exception {
        byte[] content = workbook(List.<Object[]>of(row("IMP-1", "Imported hammer", "19.99", 5, KNOWN_CATEGORY)));

        mockMvc.perform(multipart(SUBMIT_PATH)
                        .file(new MockMultipartFile("file", "products.xlsx",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                        .with(TestPrincipals.editor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("file-action.forbidden"));

        mockMvc.perform(get(TEMPLATE_PATH).with(TestPrincipals.editor()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the envelope carries the platform operation, so a generic client can poll it")
    void theEnvelopeIsThePlatformOne() throws Exception {
        String id = idOf(submit(workbook(List.<Object[]>of(
                row("IMP-1", "Imported hammer", "19.99", 5, KNOWN_CATEGORY)))).andReturn());

        mockMvc.perform(get(SUBMIT_PATH + "/{id}", id).with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.id").value(id))
                .andExpect(jsonPath("$.operation.status").value("PENDING"))
                .andExpect(jsonPath("$.operation.detail").value("VALIDATED"))
                .andExpect(header().exists("Retry-After"));
    }

    private org.springframework.test.web.servlet.ResultActions submit(byte[] content) throws Exception {
        return submitBytes(content);
    }

    private org.springframework.test.web.servlet.ResultActions submitBytes(byte[] content)
            throws Exception {
        return mockMvc.perform(multipart(SUBMIT_PATH)
                .file(new MockMultipartFile("file", "products.xlsx",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                .with(TestPrincipals.admin()));
    }

    private String idOf(MvcResult result) {
        try {
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            return body.get("id").asText();
        } catch (IOException unreadable) {
            throw new IllegalStateException("the submission response could not be read", unreadable);
        }
    }

    private static Object[] row(String sku, String name, String price, int stock, String category) {
        return new Object[] {sku, name, new java.math.BigDecimal(price).doubleValue(), stock, category};
    }

    private static byte[] workbook(List<Object[]> rows) throws IOException {
        return workbookWithHeadings(List.of("SKU", "Name", "Price", "Stock", "Category"), rows);
    }

    /** A sheet whose Price cell is text rather than a number, for the cell-level reject case. */
    private static byte[] workbookWithText(String sku, String name, String price, String stock,
                                           String category) throws IOException {
        return workbookWithHeadings(List.of("SKU", "Name", "Price", "Stock", "Category"),
                List.<Object[]>of(new Object[] {sku, name, price, stock, category}));
    }

    private static byte[] workbookWithHeadings(List<String> headings, List<Object[]> rows)
            throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            SXSSFSheet sheet = workbook.createSheet("Products");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < headings.size(); i++) {
                header.createCell(i).setCellValue(headings.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                Object[] values = rows.get(r);
                for (int c = 0; c < values.length; c++) {
                    Object value = values[c];
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof Number number) {
                        row.createCell(c).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(value.toString());
                    }
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            workbook.dispose();
            return bytes.toByteArray();
        }
    }
}
