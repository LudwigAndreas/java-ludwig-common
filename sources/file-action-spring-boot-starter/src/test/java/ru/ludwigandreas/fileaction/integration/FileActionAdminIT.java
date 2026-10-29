package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.DeferredSubmissionWorker;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.SubmissionStore;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code fileactions} area through MVC dispatch and the administrative guard, over the real submission table.
 */
@SpringBootTest(classes = FileActionTestApplication.class,
        properties = {
            "ludwig.file-action.storage.uploads=file:///tmp/ludwig-file-action-it/uploads",
            "ludwig.file-action.storage.artifacts=file:///tmp/ludwig-file-action-it/artifacts",
            "ludwig.file-action.scanning.mode=DISABLED",
            "ludwig.file-action.actions.order-import.commit-policy=PER_ROW",
            "ludwig.file-action.actions.order-import.mode=DIRECT",
            "ludwig.file-action.actions.order-import.execution=DEFERRED",
            "ludwig.file-action.deferred.initial-delay=PT1H",
            "ludwig.file-action.storage.retention-interval=PT1H",
            "ludwig.security.jwt.enabled=false",
            "ludwig.admin.grants.fileactions.read[0]=IMPORT_SUPPORT",
            "ludwig.admin.grants.fileactions.read[1]=IMPORT_OPERATOR",
            "ludwig.admin.grants.fileactions.operate[0]=IMPORT_OPERATOR"})
@AutoConfigureMockMvc
class FileActionAdminIT extends PostgresBackedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SUBMISSIONS = "/admin/v1/fileactions/submissions";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FileActionService service;

    @Autowired
    private RecordingOrderHandler handler;

    @Autowired
    private FileActionSubmissionRepository submissions;

    @Autowired
    private SubmissionStore store;

    @Autowired
    private FileActionRegistry registry;

    @Autowired
    private ObjectStore objectStore;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        handler.reset();
        jdbc.update("delete from file_action_row_reject");
        jdbc.update("delete from file_action_submission");
    }

    private SubmissionSnapshot submit() {
        return service.submit("order-import", () -> new ByteArrayInputStream(Fixtures.orders(3)),
                "orders-" + UUID.randomUUID() + ".xlsx", SourceFormat.XLSX.mediaType());
    }

    private DeferredSubmissionWorker worker() {
        return new DeferredSubmissionWorker(registry, submissions, store, service, objectStore, "instance-a",
                Duration.ofMinutes(5), 5, 3, clock);
    }

    private static RequestPostProcessor operator() {
        return TestPrincipalBuilder.user("olga").roles("IMPORT_OPERATOR").postProcessor();
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").roles("IMPORT_SUPPORT").postProcessor();
    }

    @Test
    @DisplayName("support lists every submitter's submissions with the submitter, and no file or report link")
    void listsSubmissions() throws Exception {
        SubmissionSnapshot submitted = submit();

        String body = mvc.perform(get(SUBMISSIONS).param("$filter", "submittedBy eq 'it-user'").with(support()))
                .andReturn().getResponse().getContentAsString();

        JsonNode page = JSON.readTree(body);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(submitted.id().toString());
        assertThat(page.path("items").get(0).path("submitter").asText()).isEqualTo("it-user");
        assertThat(body).doesNotContain("errorReport").doesNotContain("objectUri").doesNotContain("file:///");
    }

    @Test
    @DisplayName("filters on submitter and stored state narrow the listing; the declared file name is not filterable")
    void filters() throws Exception {
        submit();

        String otherSubmitter = mvc.perform(get(SUBMISSIONS).param("$filter", "submittedBy eq 'nobody'")
                        .with(support()))
                .andReturn().getResponse().getContentAsString();
        String pending = mvc.perform(get(SUBMISSIONS).param("$filter", "state in ('UPLOADED', 'VALIDATED')")
                        .with(support()))
                .andReturn().getResponse().getContentAsString();
        String succeeded = mvc.perform(get(SUBMISSIONS).param("$filter", "state eq 'APPLIED'").with(support()))
                .andReturn().getResponse().getContentAsString();

        assertThat(JSON.readTree(otherSubmitter).path("items")).isEmpty();
        assertThat(JSON.readTree(pending).path("items")).hasSize(1);
        assertThat(JSON.readTree(succeeded).path("items")).isEmpty();
        assertThat(mvc.perform(get(SUBMISSIONS).param("$filter", "declaredFilename eq 'x'").with(support()))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("an operator cancels another user's deferred submission: 202, and the worker does not apply it")
    void cancelsAnotherUsersSubmission() throws Exception {
        SubmissionSnapshot submitted = submit();

        MvcResult result = mvc.perform(post(SUBMISSIONS + "/" + submitted.id() + "/cancel").with(operator()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(submissions.findById(submitted.id()).orElseThrow().isCancellationRequested()).isTrue();

        worker().pollOnce();

        assertThat(handler.applied()).isEmpty();
        assertThat(submissions.findById(submitted.id()).orElseThrow().getRowsApplied()).isZero();
        assertThat(submissions.findById(submitted.id()).orElseThrow().getState())
                .isEqualTo(FileActionState.CANCELLED);
    }

    @Test
    @DisplayName("an operator cancels a submission awaiting confirmation: 200, CANCELLED at once")
    void cancelsAValidatedSubmission() throws Exception {
        SubmissionSnapshot submitted = submit();
        // This context's action applies directly; the row is put where a CONFIRM action's would be.
        jdbc.update("update file_action_submission set state = 'VALIDATED' where id = ?", submitted.id());

        MvcResult result = mvc.perform(post(SUBMISSIONS + "/" + submitted.id() + "/cancel").with(operator()))
                .andReturn();

        assertThat(jdbc.queryForObject("select state from file_action_submission where id = ?", String.class,
                submitted.id())).isEqualTo("CANCELLED");
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("operation").path("status")
                .asText()).isEqualTo("CANCELLED");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("cancelled submissions are listed under status=CANCELLED")
    void listsCancelled() throws Exception {
        SubmissionSnapshot submitted = submit();
        mvc.perform(post(SUBMISSIONS + "/" + submitted.id() + "/cancel").with(operator()));
        worker().pollOnce();

        String cancelled = mvc.perform(get(SUBMISSIONS).param("$filter", "state eq 'CANCELLED'").with(support()))
                .andReturn().getResponse().getContentAsString();

        assertThat(JSON.readTree(cancelled).path("items")).hasSize(1);
        assertThat(JSON.readTree(cancelled).path("items").get(0).path("id").asText())
                .isEqualTo(submitted.id().toString());
    }

    @Test
    @DisplayName("cancelling a terminal submission answers 200 with its envelope")
    void cancelTerminal() throws Exception {
        SubmissionSnapshot submitted = submit();
        jdbc.update("update file_action_submission set state = 'EXPIRED' where id = ?", submitted.id());

        MvcResult again = mvc.perform(post(SUBMISSIONS + "/" + submitted.id() + "/cancel").with(operator()))
                .andReturn();

        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(again.getResponse().getContentAsString()).path("operation").path("status")
                .asText()).isEqualTo("EXPIRED");
        assertThat(submissions.findById(submitted.id()).orElseThrow().isCancellationRequested()).isFalse();
    }

    @Test
    @DisplayName("read alone may not cancel, and an unknown submission is 404")
    void refusals() throws Exception {
        SubmissionSnapshot submitted = submit();

        int forbidden = mvc.perform(post(SUBMISSIONS + "/" + submitted.id() + "/cancel").with(support()))
                .andReturn().getResponse().getStatus();
        int missing = mvc.perform(get(SUBMISSIONS + "/" + UUID.randomUUID()).with(support()))
                .andReturn().getResponse().getStatus();

        assertThat(forbidden).isEqualTo(403);
        assertThat(submissions.findById(submitted.id()).orElseThrow().isCancellationRequested()).isFalse();
        assertThat(missing).isEqualTo(404);
    }
}
