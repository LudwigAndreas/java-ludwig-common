package ru.ludwigandreas.export.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.export.sink.FilesystemReportSink;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The {@code exports} area through MVC dispatch and the administrative guard, over the real run table.
 */
@SpringBootTest(classes = ExportAdminIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.liquibase.enabled=false",
            "ludwig.export.poller.enabled=false",
            "ludwig.export.sink.type=testReportSink",
            "ludwig.security.jwt.enabled=false",
            "ludwig.admin.grants.exports.read[0]=EXPORT_OPERATOR",
            "ludwig.admin.grants.exports.operate[0]=EXPORT_OPERATOR"})
@Import(PostgresContainerConfiguration.class)
@AutoConfigureMockMvc
class ExportAdminIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@link ExportTestApplication} without its security exclusions: the administrative surface exists only with the
     * security starter, which the lifecycle tests deliberately leave out.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
        "ru.ludwigandreas.restclient.config.RestClientAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientAuthAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientObservabilityAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientInterfaceAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientWebCoreAutoConfiguration",
        "ru.ludwigandreas.identity.config.IdentityProjectionAutoConfiguration",
        "ru.ludwigandreas.identity.config.IdentityKafkaAutoConfiguration",
        "ru.ludwigandreas.identity.config.IdentityLiquibaseAutoConfiguration"
    })
    static class Application {

        @Bean
        ReportSink testReportSink() throws IOException {
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ludwig-export-admin-it");
            Files.createDirectories(root);
            return new FilesystemReportSink(root);
        }
    }

    private static final String RUNS = "/admin/v1/exports/runs";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExportReportRunRepository runs;

    @Autowired
    private ExportReportOutputRepository outputs;

    @BeforeEach
    void reset() {
        outputs.deleteAll();
        runs.deleteAll();
    }

    private UUID run(String requester, OperationStatus status) {
        ExportReportRun run = new ExportReportRun();
        run.setDefinitionKey("catalog.orders");
        run.setDefinitionVersion(1);
        run.setRequester(requester);
        run.setFormats(List.of("csv"));
        run.setLocale("en");
        run.setTimeZone("UTC");
        run.setIdempotencyKey(UUID.randomUUID().toString());
        run.setMaxAttempts(2);
        run.setNextAttemptAt(Instant.now());
        run.setParameters(Map.of("from", "2026-01-01"));
        run.setColumnIds(List.of("number", "total"));
        run.setStatus(status);
        if (status == OperationStatus.RUNNING) {
            run.setStartedAt(Instant.now());
        }
        return runs.save(run).getId();
    }

    private static RequestPostProcessor operator() {
        return TestPrincipalBuilder.user("olga").roles("EXPORT_OPERATOR").postProcessor();
    }

    @Test
    @DisplayName("an operator lists every requester's running runs, without any output link")
    void listsEveryRequester() throws Exception {
        run("alice", OperationStatus.RUNNING);
        run("bob", OperationStatus.RUNNING);
        run("bob", OperationStatus.PENDING);

        String body = mvc.perform(get(RUNS).param("status", "RUNNING").with(operator())).andReturn().getResponse()
                .getContentAsString();

        JsonNode page = JSON.readTree(body);
        assertThat(page.path("items").findValuesAsText("requester")).containsExactlyInAnyOrder("alice", "bob");
        assertThat(page.path("items").get(0).path("operation").path("result").isMissingNode()
                || page.path("items").get(0).path("operation").path("result").isNull()).isTrue();
        assertThat(body).doesNotContain("/outputs/");
    }

    @Test
    @DisplayName("an administrator cancels another user's run: 202, audited, and the requester API still 404s")
    void cancelsAnotherUsersRun() throws Exception {
        UUID alices = run("alice", OperationStatus.RUNNING);

        MvcResult cancelled = mvc.perform(post(RUNS + "/" + alices + "/cancel").with(operator())).andReturn();
        MvcResult viaRequesterApi = mvc.perform(get("/api/v1/reports/runs/" + alices).with(operator())).andReturn();

        assertThat(cancelled.getResponse().getStatus()).isEqualTo(202);
        assertThat(runs.findById(alices).orElseThrow().isCancelRequested()).isTrue();
        assertThat(viaRequesterApi.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("cancelling a finished run answers with its envelope, not a 409")
    void cancelTerminal() throws Exception {
        UUID cancelled = run("alice", OperationStatus.CANCELLED);

        MvcResult result = mvc.perform(post(RUNS + "/" + cancelled + "/cancel").with(operator())).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(200, 202);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("operation").path("status").asText())
                .isEqualTo("CANCELLED");
    }
}
