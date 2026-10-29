package ru.ludwigandreas.reconciliation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.engine.RunFailureCodes;
import ru.ludwigandreas.reconciliation.engine.RunPurge;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.entity.RunKind;
import ru.ludwigandreas.reconciliation.entity.SyncRun;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalOrder;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalOrderRepository;
import ru.ludwigandreas.reconciliation.integration.testmodel.PartnerCatalogueTask;
import ru.ludwigandreas.reconciliation.integration.testmodel.RecordingAuditSink;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubPartner;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRunRepository;
import ru.ludwigandreas.reconciliation.repository.SyncTaskStateRepository;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Runs as readable resources: an operator's run answers at once and is polled to its end, a scheduled run is on the
 * record with how it ended, and old or abandoned history is cleaned up.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.enabled=true",
            "ludwig.security.jwt.enabled=false",
            "ludwig.admin.grants.reconciliation.read[0]=SYNC_OPERATOR",
            "ludwig.admin.grants.reconciliation.operate[0]=SYNC_OPERATOR",
            "ludwig.admin.grants.reconciliation.privileged[0]=SYNC_ADMIN"})
@Import(PostgresContainerConfiguration.class)
@AutoConfigureMockMvc
class ReconciliationRunHistoryIntegrationTest {

    private static final String BILLING = "/admin/v1/reconciliation/tasks/billing-status";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant LATER = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StubPartner partner;

    @Autowired
    private PartnerCatalogueTask catalogueTask;

    @Autowired
    private LocalOrderRepository orders;

    @Autowired
    private SyncInboxRecordRepository records;

    @Autowired
    private SyncTaskStateRepository taskStates;

    @Autowired
    private SyncRunRepository runs;

    @Autowired
    private TaskRunner runner;

    @Autowired
    private TaskRegistry registry;

    @Autowired
    private RunPurge purge;

    @Autowired
    private RunLock runLock;

    @Autowired
    private RecordingAuditSink audit;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void resetState() {
        partner.reset();
        catalogueTask.failOnPageAt(-1);
        audit.reset();
        transactions.executeWithoutResult(status -> {
            records.deleteAllInBatch();
            taskStates.deleteAllInBatch();
            orders.deleteAllInBatch();
            runs.deleteAllInBatch();
        });
    }

    private static RequestPostProcessor operator() {
        return TestPrincipalBuilder.user("olga").roles("SYNC_OPERATOR").postProcessor();
    }

    private void givenOrder(String externalId, String status) {
        transactions.executeWithoutResult(ignored -> {
            LocalOrder order = new LocalOrder();
            order.setExternalId(externalId);
            order.setStatus(status);
            orders.save(order);
        });
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).with(operator()))
                .andReturn();
    }

    /** Polls a run's status resource until it is terminal, as a client following {@code Location} would. */
    private JsonNode pollToEnd(String location) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mvc.perform(get(location).with(operator())).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
            String status = body.path("operation").path("status").asText();
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                assertThat(result.getResponse().getHeader("Retry-After")).isNull();
                return body;
            }
            assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
            Thread.sleep(50);
        }
        throw new AssertionError("run at " + location + " did not finish within 30 seconds");
    }

    @Test
    void aManualRunAnswersAtOnceAndIsPolledToItsEnd() throws Exception {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);

        MvcResult started = postJson(BILLING + "/runs", "{\"tier\":\"hot\"}");

        assertThat(started.getResponse().getStatus()).isEqualTo(202);
        JsonNode accepted = JSON.readTree(started.getResponse().getContentAsString());
        String runId = accepted.path("operation").path("id").asText();
        assertThat(started.getResponse().getHeader("Location")).isEqualTo(BILLING + "/runs/" + runId);
        assertThat(accepted.path("kind").asText()).isEqualTo("MANUAL");
        assertThat(accepted.path("triggeredBy").asText()).isEqualTo("olga");

        JsonNode finished = pollToEnd(BILLING + "/runs/" + runId);

        assertThat(finished.path("operation").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.path("operation").path("result").path("href").asText())
                .isEqualTo(BILLING + "/runs/" + runId);
        assertThat(finished.path("demanded").asInt()).isEqualTo(1);
        assertThat(finished.path("fetched").asInt()).isEqualTo(1);
        assertThat(finished.path("produced").asInt()).isEqualTo(1);
        assertThat(finished.path("instance").asText()).isNotBlank();
    }

    @Test
    void aRunIsRefusedWhileTheTaskIsRunningOnAnyReplica() throws Exception {
        Optional<RunLockHandle> elsewhere =
                runLock.tryAcquire("reconciliation:billing-status:hot", Duration.ofMinutes(5));
        assertThat(elsewhere).isPresent();
        try (RunLockHandle held = elsewhere.get()) {
            MvcResult refused = postJson(BILLING + "/runs", "{}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(runs.findAll()).isEmpty();
    }

    @Test
    void aScheduledRunThatFailedIsListedAsFailedWithItsCode() throws Exception {
        catalogueTask.failOnPageAt(0);
        runner.run(registry.find("partner-catalogue").orElseThrow(), DemandTier.HOT);

        MvcResult listed = mvc.perform(get("/admin/v1/reconciliation/tasks/partner-catalogue/runs")
                .param("$filter", "kind eq 'SCHEDULED' and outcome eq 'FAILURE'").with(operator())).andReturn();

        JsonNode items = JSON.readTree(listed.getResponse().getContentAsString()).path("items");
        assertThat(items).hasSize(1);
        JsonNode run = items.get(0);
        assertThat(run.path("kind").asText()).isEqualTo("SCHEDULED");
        assertThat(run.path("operation").path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("operation").path("failure").path("code").asText())
                .isEqualTo(RunFailureCodes.RUN_FAILED);
        assertThat(run.path("operation").path("startedAt").isNull()).isFalse();
        assertThat(run.path("operation").path("finishedAt").isNull()).isFalse();
    }

    @Test
    void theRunListingFiltersWithinThePathsTaskAndRefusesTheResultBody() throws Exception {
        catalogueTask.failOnPageAt(0);
        runner.run(registry.find("partner-catalogue").orElseThrow(), DemandTier.HOT);

        MvcResult manualOnly = mvc.perform(get("/admin/v1/reconciliation/tasks/partner-catalogue/runs")
                .param("$filter", "kind eq 'MANUAL'").with(operator())).andReturn();
        MvcResult otherTask = mvc.perform(get("/admin/v1/reconciliation/tasks/billing-status/runs")
                .param("$filter", "outcome eq 'FAILURE'").with(operator())).andReturn();
        MvcResult resultBody = mvc.perform(get("/admin/v1/reconciliation/tasks/partner-catalogue/runs")
                .param("$filter", "resultJson eq 'x'").with(operator())).andReturn();

        assertThat(JSON.readTree(manualOnly.getResponse().getContentAsString()).path("items")).isEmpty();
        assertThat(JSON.readTree(otherTask.getResponse().getContentAsString()).path("items")).isEmpty();
        assertThat(resultBody.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aDryRunsReportIsItsResultAndNothingIsStaged() throws Exception {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);

        MvcResult started = postJson(BILLING + "/runs", "{\"dryRun\":true}");
        JsonNode finished = pollToEnd(started.getResponse().getHeader("Location"));

        assertThat(finished.path("kind").asText()).isEqualTo("DRY_RUN");
        assertThat(finished.path("result").path("ran").asBoolean()).isTrue();
        assertThat(finished.path("result").path("demandSize").asInt()).isEqualTo(1);
        assertThat(records.findAll()).isEmpty();
    }

    @Test
    void aBackfillIsARunWhoseResultCountsWhatWasStaged() throws Exception {
        givenOrder("A-1", "SETTLED");
        partner.holds("A-1", "REFUNDED", LATER);

        MvcResult started = postJson(BILLING + "/backfills", "{\"keys\":[\"A-1\"]}");
        assertThat(started.getResponse().getStatus()).isEqualTo(202);
        JsonNode finished = pollToEnd(started.getResponse().getHeader("Location"));

        assertThat(finished.path("kind").asText()).isEqualTo("BACKFILL");
        assertThat(finished.path("result").path("requested").asInt()).isEqualTo(1);
        assertThat(finished.path("result").path("staged").asInt()).isEqualTo(1);
    }

    @Test
    void theRunsAuditEventsCarryItsIdAndCorrelationId() throws Exception {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);

        MvcResult started = postJson(BILLING + "/runs", "{}");
        JsonNode finished = pollToEnd(started.getResponse().getHeader("Location"));
        String runId = finished.path("operation").path("id").asText();
        String correlationId = finished.path("operation").path("correlationId").asText();

        List<AuditEvent> runEvents = new ArrayList<>(audit.withAction("run.started"));
        runEvents.addAll(audit.withAction("run.finished"));
        assertThat(runEvents).filteredOn(event -> runId.equals(event.attributes().get("runId"))).hasSize(2)
                .allSatisfy(event -> assertThat(event.correlationId()).isEqualTo(correlationId));
    }

    @Test
    void runsArePagedNewestFirst() throws Exception {
        for (int i = 0; i < 3; i++) {
            runner.run(registry.find("billing-status").orElseThrow(), DemandTier.HOT);
        }

        JsonNode first = JSON.readTree(mvc.perform(get(BILLING + "/runs").param("size", "2").with(operator()))
                .andReturn().getResponse().getContentAsString());
        JsonNode second = JSON.readTree(mvc.perform(get(BILLING + "/runs").param("size", "2")
                .param("cursor", first.path("next").asText()).with(operator())).andReturn().getResponse()
                .getContentAsString());

        assertThat(first.path("items")).hasSize(2);
        assertThat(second.path("items")).hasSize(1);
        assertThat(first.path("items").get(0).path("submittedAt").asText())
                .isGreaterThanOrEqualTo(first.path("items").get(1).path("submittedAt").asText());
    }

    @Test
    void anOldRunIsPurgedAndAnAbandonedOneIsEnded() throws Exception {
        UUID old = UUID.randomUUID();
        UUID abandoned = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            runs.save(run(old, Instant.now().minus(Duration.ofDays(31)), true));
            runs.save(run(abandoned, Instant.now().minus(Duration.ofHours(1)), false));
        });

        purge.runNow();

        assertThat(mvc.perform(get(BILLING + "/runs/" + old).with(operator())).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
        JsonNode ended = JSON.readTree(mvc.perform(get(BILLING + "/runs/" + abandoned).with(operator()))
                .andReturn().getResponse().getContentAsString());
        assertThat(ended.path("operation").path("status").asText()).isEqualTo("FAILED");
        assertThat(ended.path("operation").path("failure").path("code").asText())
                .isEqualTo(RunFailureCodes.RUN_ABANDONED);
    }

    private static SyncRun run(UUID id, Instant at, boolean finished) {
        SyncRun run = new SyncRun();
        run.setId(id);
        run.setTaskName("billing-status");
        run.setTier(DemandTier.HOT);
        run.setKind(RunKind.SCHEDULED);
        run.setSubmittedAt(at);
        run.setStartedAt(at);
        if (finished) {
            run.setFinishedAt(at);
            run.setOutcome(RunOutcome.SUCCESS);
        }
        return run;
    }
}
