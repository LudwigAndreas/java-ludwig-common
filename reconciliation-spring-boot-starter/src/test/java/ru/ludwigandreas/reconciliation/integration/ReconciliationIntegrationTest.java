package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.engine.ApplyService;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.entity.SyncRecordKind;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalOrder;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalOrderRepository;
import ru.ludwigandreas.reconciliation.integration.testmodel.PartnerCatalogueTask;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubPartner;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncTaskStateRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine against a real Postgres: staging, applying, the two guard rails, failure isolation,
 * quarantine and requeue, cursor checkpointing and the run lock.
 *
 * <p>Schema applied by the module's own shipped changelog and cross-checked against the JPA mappings
 * by {@code ddl-auto=validate}, which is the arrangement a consuming service actually has.
 *
 * <p>Nothing is scheduled: every pass is driven by hand, because an assertion about what one pass did
 * is only meaningful when no other pass is running at the same time.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Testcontainers
class ReconciliationIntegrationTest {

    /**
     * Pinned by name, version <em>and</em> digest: a tag alone can be re-pointed at different content,
     * so tests would silently change what they execute.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-01T11:00:00Z");

    // The connection name is given explicitly because Spring Boot otherwise deduces it by parsing the
    // image name, and a name carrying both a tag and a digest is not parseable as a repository.
    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private TaskRegistry registry;

    @Autowired
    private TaskRunner runner;

    @Autowired
    private ApplyService applyService;

    @Autowired
    private TaskStateService taskState;

    @Autowired
    private StubPartner partner;

    @Autowired
    private LocalOrderRepository orders;

    @Autowired
    private SyncInboxRecordRepository records;

    @Autowired
    private SyncTaskStateRepository taskStates;

    @Autowired
    private PartnerCatalogueTask catalogueTask;

    @Autowired
    private RunLock runLock;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void resetState() {
        partner.reset();
        catalogueTask.failOnPageAt(-1);
        transactions.executeWithoutResult(status -> {
            records.deleteAllInBatch();
            taskStates.deleteAllInBatch();
            orders.deleteAllInBatch();
        });
    }

    @Test
    void fetchStagesAndApplyAppliesTheExternalStatus() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);

        int staged = runner.run(task("billing-status"), DemandTier.HOT);
        assertThat(staged).isEqualTo(1);
        assertThat(order("A-1").getStatus()).isEqualTo("PENDING");

        int applied = applyService.runOnce(task("billing-status"));

        assertThat(applied).isEqualTo(1);
        assertThat(order("A-1").getStatus()).isEqualTo("PAID");
        assertThat(records.findAll().get(0).getStatus()).isEqualTo(SyncRecordStatus.APPLIED);
    }

    /**
     * The point of two phases: a retry works from the row, and the partner is not asked again.
     */
    @Test
    void aRetryOfAnApplyDoesNotCallThePartnerAgain() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);
        runner.run(task("billing-status"), DemandTier.HOT);

        int callsAfterFetch = partner.calls().size();
        applyService.runOnce(task("billing-status"));
        applyService.runOnce(task("billing-status"));

        assertThat(partner.calls()).hasSize(callsAfterFetch);
    }

    /** The idempotency short-circuit, end to end: a second identical fetch changes nothing. */
    @Test
    void anUnchangedPayloadSettlesWithoutTouchingTheLocalRecord() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);
        runAndApply("billing-status");
        assertThat(order("A-1").getSyncCount()).isEqualTo(1);

        runAndApply("billing-status");

        assertThat(order("A-1").getSyncCount()).isEqualTo(1);
        assertThat(records.findAll()).anySatisfy(record ->
                assertThat(record.getStatus()).isEqualTo(SyncRecordStatus.UNCHANGED));
    }

    /**
     * Stale-write protection. The worst bug class here precisely because it is invisible: an
     * out-of-order response silently regressing a record's status, with no error and no failed metric.
     */
    @Test
    void anOlderResponseArrivingLastDoesNotRegressTheLocalRecord() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "REFUNDED", LATER);
        runAndApply("billing-status");
        assertThat(order("A-1").getStatus()).isEqualTo("REFUNDED");

        // The partner now answers with older state - a retried call that was slow, say.
        partner.holds("A-1", "PAID", EARLIER);
        runAndApply("billing-status");

        assertThat(order("A-1").getStatus()).isEqualTo("REFUNDED");
        assertThat(records.findAll()).anySatisfy(record ->
                assertThat(record.getStatus()).isEqualTo(SyncRecordStatus.REJECTED));
    }

    @Test
    void aKeyThePartnerDoesNotKnowIsNotStagedUnderTheDefaultPolicy() {
        givenOrder("A-1", "PENDING");
        partner.doesNotKnow("A-1");

        runner.run(task("billing-status"), DemandTier.HOT);

        assertThat(records.findAll()).isEmpty();
        assertThat(order("A-1").getStatus()).isEqualTo("PENDING");
    }

    /** Per-item isolation: one unmappable record settles itself, not the run. */
    @Test
    void oneRecordThatCannotBeAppliedDoesNotStopTheOthers() {
        givenOrder("A-1", "PENDING");
        givenOrder("A-2", "PENDING");
        partner.holds("A-1", "EXPLODE", LATER);
        partner.holds("A-2", "PAID", LATER);

        runAndApply("billing-status");

        assertThat(order("A-2").getStatus()).isEqualTo("PAID");
        assertThat(order("A-1").getStatus()).isEqualTo("PENDING");
        assertThat(recordFor("A-1").getStatus()).isEqualTo(SyncRecordStatus.FAILED);
    }

    @Test
    void aRecordThatKeepsFailingIsQuarantinedAndCanBeRequeued() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "EXPLODE", LATER);
        runner.run(task("billing-status"), DemandTier.HOT);

        // max-attempts is 2 for this task, so two passes exhaust the budget.
        applyService.runOnce(task("billing-status"));
        makeDue();
        applyService.runOnce(task("billing-status"));

        assertThat(recordFor("A-1").getStatus()).isEqualTo(SyncRecordStatus.QUARANTINED);

        records.requeueQuarantined("billing-status", Instant.now());
        SyncInboxRecord requeued = recordFor("A-1");

        assertThat(requeued.getStatus()).isEqualTo(SyncRecordStatus.STAGED);
        assertThat(requeued.getAttempts()).isZero();
    }

    /**
     * A key the partner reliably chokes on backs off and eventually stops being fetched, instead of
     * being refetched on every run forever with nothing to show for it.
     */
    @Test
    void aKeyWhoseFetchKeepsFailingIsSuppressedAndThenQuarantined() {
        givenOrder("A-1", "PENDING");
        partner.failsOn("A-1");

        runner.run(task("billing-status"), DemandTier.HOT);
        assertThat(recordFor("A-1").getKind()).isEqualTo(SyncRecordKind.FETCH_FAILURE);
        assertThat(recordFor("A-1").getStatus()).isEqualTo(SyncRecordStatus.FAILED);

        makeDue();
        runner.run(task("billing-status"), DemandTier.HOT);

        assertThat(recordFor("A-1").getStatus()).isEqualTo(SyncRecordStatus.QUARANTINED);
        assertThat(records.findSuppressedKeys("billing-status", Instant.now())).contains("A-1");
    }

    @Test
    void aFetchFailureThatRecoversStopsSuppressingTheKey() {
        givenOrder("A-1", "PENDING");
        partner.failsOn("A-1");
        runner.run(task("billing-status"), DemandTier.HOT);

        partner.recoversOn("A-1").holds("A-1", "PAID", LATER);
        makeDue();
        runAndApply("billing-status");

        assertThat(order("A-1").getStatus()).isEqualTo("PAID");
        assertThat(records.findSuppressedKeys("billing-status", Instant.now())).doesNotContain("A-1");
    }

    /**
     * Exactly-once run semantics. A second instance holding the lock is not a failure, and the run it
     * prevented is simply not run - which is the whole point of the lock.
     */
    @Test
    void aSecondInstanceDoesNotRunATaskAnotherInstanceIsAlreadyRunning() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);

        Optional<RunLockHandle> heldElsewhere =
                runLock.tryAcquire("reconciliation:billing-status:hot", Duration.ofMinutes(5));
        assertThat(heldElsewhere).isPresent();

        try (RunLockHandle ignored = heldElsewhere.get()) {
            assertThat(runner.run(task("billing-status"), DemandTier.HOT)).isZero();
            assertThat(partner.calls()).isEmpty();
        }

        assertThat(runner.run(task("billing-status"), DemandTier.HOT)).isEqualTo(1);
    }

    @Test
    void apagedSweepWalksEveryPageAndClearsItsCursorWhenItFinishes() {
        givenOrder("A-1", "PENDING");
        givenOrder("A-2", "PENDING");
        givenOrder("A-3", "PENDING");
        partner.holds("A-1", "PAID", LATER).holds("A-2", "PAID", LATER).holds("A-3", "PAID", LATER);

        runAndApply("partner-catalogue");

        assertThat(order("A-1").getStatus()).isEqualTo("PAID");
        assertThat(order("A-3").getStatus()).isEqualTo("PAID");
        assertThat(taskState.cursor("partner-catalogue", DemandTier.HOT)).isEmpty();
    }

    /**
     * The reason the cursor is persisted at all: a sweep interrupted half way resumes from where it
     * got to rather than re-reading everything it already staged.
     */
    @Test
    void apagedSweepInterruptedMidWalkResumesFromItsCheckpoint() {
        givenOrder("A-1", "PENDING");
        givenOrder("A-2", "PENDING");
        givenOrder("A-3", "PENDING");
        givenOrder("A-4", "PENDING");
        partner.holds("A-1", "PAID", LATER).holds("A-2", "PAID", LATER)
                .holds("A-3", "PAID", LATER).holds("A-4", "PAID", LATER);

        catalogueTask.failOnPageAt(2);
        runner.run(task("partner-catalogue"), DemandTier.HOT);

        assertThat(taskState.cursor("partner-catalogue", DemandTier.HOT)).contains("2");
        assertThat(records.findAll()).hasSize(2);

        catalogueTask.failOnPageAt(-1);
        int pagesBefore = partner.pageCalls();
        runAndApply("partner-catalogue");

        // One more page, not two: the sweep resumed at offset 2 and finished there, instead of
        // starting again at offset 0 and re-reading the records it had already staged.
        assertThat(partner.pageCalls() - pagesBefore).isEqualTo(1);
        assertThat(order("A-3").getStatus()).isEqualTo("PAID");
        assertThat(order("A-4").getStatus()).isEqualTo("PAID");
        assertThat(taskState.cursor("partner-catalogue", DemandTier.HOT)).isEmpty();
    }

    @Test
    void aCompletedRunRecordsItsWatermarkFromTheRecordsItActuallySaw() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);

        runner.run(task("billing-status"), DemandTier.HOT);

        assertThat(taskState.watermark("billing-status", DemandTier.HOT)).contains(LATER);
    }

    /** The watermark never moves backwards, so a partner reporting older state does not re-sweep history. */
    @Test
    void theWatermarkNeverMovesBackwards() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);
        runner.run(task("billing-status"), DemandTier.HOT);

        partner.reset();
        partner.holds("A-1", "PAID", EARLIER);
        runner.run(task("billing-status"), DemandTier.HOT);

        assertThat(taskState.watermark("billing-status", DemandTier.HOT)).contains(LATER);
    }

    @Test
    void aRecordStrandedByAnInstanceThatDiedIsReclaimed() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);
        runner.run(task("billing-status"), DemandTier.HOT);

        transactions.executeWithoutResult(status -> {
            SyncInboxRecord record = records.findAll().get(0);
            record.setStatus(SyncRecordStatus.PROCESSING);
            record.setLockedAt(Instant.now().minus(Duration.ofHours(1)));
            record.setLockedBy("an-instance-that-died");
            records.save(record);
        });

        int reclaimed = records.reclaimStale(Instant.now().minus(Duration.ofMinutes(5)));

        assertThat(reclaimed).isEqualTo(1);
        assertThat(records.findAll().get(0).getStatus()).isEqualTo(SyncRecordStatus.STAGED);
    }

    /**
     * The dry run is what an operator reaches for before turning a task on: it must say what the
     * partner would answer, and write absolutely nothing.
     */
    @Test
    void aDryRunReportsWhatWouldHappenAndStagesNothing() {
        givenOrder("A-1", "PENDING");
        givenOrder("A-2", "PENDING");
        partner.holds("A-1", "PAID", LATER);
        partner.doesNotKnow("A-2");

        Map<String, Object> report = runner.dryRun(task("billing-status"), DemandTier.HOT);

        assertThat(report).containsEntry("ran", true).containsEntry("demandSize", 2);
        assertThat(report).containsKey("found").containsKey("notFound");
        assertThat(records.findAll()).isEmpty();
        assertThat(order("A-1").getStatus()).isEqualTo("PENDING");
        assertThat(taskState.watermark("billing-status", DemandTier.HOT)).isEmpty();
    }

    @Test
    void aBackfillFetchesTheKeysItIsGivenRatherThanTheDemandQuery() {
        givenOrder("A-1", "SETTLED");
        partner.holds("A-1", "REFUNDED", LATER);

        // The hot demand query excludes SETTLED, so a scheduled run would not touch this record.
        assertThat(runner.run(task("billing-status"), DemandTier.HOT)).isZero();

        assertThat(runner.backfill(task("billing-status"), List.of("A-1"))).isEqualTo(1);
        applyService.runOnce(task("billing-status"));

        assertThat(order("A-1").getStatus()).isEqualTo("REFUNDED");
    }

    /** A backfill keeps the guard rails, so it cannot overwrite state that is newer than the partner's. */
    @Test
    void aBackfillStillRefusesStateOlderThanWhatWasAlreadyApplied() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "REFUNDED", LATER);
        runAndApply("billing-status");

        partner.holds("A-1", "PAID", EARLIER);
        runner.backfill(task("billing-status"), List.of("A-1"));
        applyService.runOnce(task("billing-status"));

        assertThat(order("A-1").getStatus()).isEqualTo("REFUNDED");
    }

    @Test
    void aBackfillDoesNotAdvanceTheWatermark() {
        givenOrder("A-1", "PENDING");
        partner.holds("A-1", "PAID", LATER);

        runner.backfill(task("billing-status"), List.of("A-1"));

        assertThat(taskState.watermark("billing-status", DemandTier.HOT)).isEmpty();
    }

    private void runAndApply(String taskName) {
        runner.run(task(taskName), DemandTier.HOT);
        applyService.runOnce(task(taskName));
    }

    private RegisteredTask<?, ?, ?> task(String name) {
        return registry.find(name).orElseThrow();
    }

    private void givenOrder(String externalId, String status) {
        transactions.executeWithoutResult(ignored -> {
            LocalOrder order = new LocalOrder();
            order.setExternalId(externalId);
            order.setStatus(status);
            orders.save(order);
        });
    }

    private LocalOrder order(String externalId) {
        return orders.findByExternalId(externalId).orElseThrow();
    }

    private SyncInboxRecord recordFor(String correlationKey) {
        List<SyncInboxRecord> matching = records.findAll().stream()
                .filter(record -> record.getCorrelationKey().equals(correlationKey))
                .toList();
        return matching.get(matching.size() - 1);
    }

    /** Brings every staged record due now, so a retry can be driven without waiting out its backoff. */
    private void makeDue() {
        transactions.executeWithoutResult(status -> records.findAll().forEach(record -> {
            record.setNextAttemptAt(Instant.now().minusSeconds(1));
            records.save(record);
        }));
    }
}
