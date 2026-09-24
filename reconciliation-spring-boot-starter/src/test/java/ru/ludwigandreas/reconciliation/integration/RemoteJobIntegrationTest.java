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
import ru.ludwigandreas.reconciliation.api.JobStatus;
import ru.ludwigandreas.reconciliation.engine.ApplyService;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalOrder;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalOrderRepository;
import ru.ludwigandreas.reconciliation.integration.testmodel.PartnerBulkStatusTask;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubPartner;
import ru.ludwigandreas.reconciliation.job.RemoteJobCollectService;
import ru.ludwigandreas.reconciliation.job.RemoteJobMaintenanceService;
import ru.ludwigandreas.reconciliation.job.RemoteJobPollService;
import ru.ludwigandreas.reconciliation.job.RemoteJobSubmitService;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.QuotaWaiterRepository;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;
import ru.ludwigandreas.reconciliation.repository.SyncTaskStateRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The asynchronous-job shape: the submit hazard, adoption, expiry, and the quota slot that is held
 * across all of it.
 *
 * <p>These are the paths that are rare in production, expensive when they go wrong, and impossible to
 * reason about from the code alone - so they are exercised against a real Postgres with a partner that
 * can be told to misbehave in exactly the ways that matter.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Testcontainers
class RemoteJobIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    private static final String TASK = "partner-bulk-status";
    private static final Instant CHANGED_AT = Instant.parse("2026-01-01T11:00:00Z");

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private TaskRegistry registry;

    @Autowired
    private RemoteJobSubmitService submitService;

    @Autowired
    private RemoteJobPollService pollService;

    @Autowired
    private RemoteJobCollectService collectService;

    @Autowired
    private RemoteJobMaintenanceService maintenanceService;

    @Autowired
    private ApplyService applyService;

    @Autowired
    private PartnerBulkStatusTask task;

    @Autowired
    private StubPartner partner;

    @Autowired
    private LocalOrderRepository orders;

    @Autowired
    private SyncRemoteJobRepository jobs;

    @Autowired
    private SyncInboxRecordRepository records;

    @Autowired
    private SyncTaskStateRepository taskStates;

    @Autowired
    private QuotaLeaseRepository leases;

    @Autowired
    private QuotaWaiterRepository waiters;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void resetState() {
        partner.reset();
        task.reset();
        transactions.executeWithoutResult(status -> {
            records.deleteAllInBatch();
            jobs.deleteAllInBatch();
            leases.deleteAllInBatch();
            waiters.deleteAllInBatch();
            taskStates.deleteAllInBatch();
            orders.deleteAllInBatch();
        });
    }

    @Test
    void aSubmitTakesAQuotaSlotBeforeTheCallAndRecordsTheHandleAfterIt() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);

        assertThat(submitService.submitPass(task())).isEqualTo(1);

        SyncRemoteJob job = onlyJob();
        assertThat(job.getState()).isEqualTo(RemoteJobState.SUBMITTED);
        assertThat(job.getExternalHandle()).isEqualTo("job-1");
        assertThat(job.getQuotaLeaseId()).isNotNull();
        assertThat(leases.findByJobId(job.getId())).isEmpty();
        assertThat(leases.findById(job.getQuotaLeaseId())).isPresent();
    }

    @Test
    void theFullSubmitPollCollectApplyCycleSyncsTheLocalRecord() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);

        submitService.submitPass(task());
        task.reports(JobStatus.succeeded());
        makeJobsDue();
        pollService.pollPass(task());
        assertThat(onlyJob().getState()).isEqualTo(RemoteJobState.SUCCEEDED);

        collectService.collectPass(task());
        assertThat(onlyJob().getState()).isEqualTo(RemoteJobState.COLLECTED);

        applyService.runOnce(task());

        assertThat(order("A-1").getStatus()).isEqualTo("PAID");
    }

    /** Demand covered by an in-flight job is not offered again, so nothing is submitted twice. */
    @Test
    void demandCoveredByAnInFlightJobIsNotResubmitted() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        submitService.submitPass(task());

        assertThat(submitService.submitPass(task())).isZero();
        assertThat(jobs.findAll()).hasSize(1);
    }

    /**
     * The submit hazard. The request reached the partner and the response did not come back. The row
     * committed before the call is the only evidence that a job may exist.
     */
    @Test
    void aSubmitWhoseResponseWasLostLeavesAnAmbiguousRowRatherThanNothing() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        task.swallowSubmitResponse(true);

        submitService.submitPass(task());

        SyncRemoteJob job = onlyJob();
        assertThat(job.getState()).isEqualTo(RemoteJobState.PENDING_SUBMIT);
        assertThat(job.getIdempotencyKey()).isNotBlank();
        assertThat(job.getQuotaLeaseId()).isNotNull();
        assertThat(task.remoteJobs()).hasSize(1);
    }

    /**
     * The clean way out: the partner can list its active jobs, so the engine matches on the committed
     * idempotency key and adopts the handle instead of guessing.
     */
    @Test
    void anAmbiguousSubmitIsResolvedByAdoptingThePartnersOwnJob() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        task.swallowSubmitResponse(true);
        submitService.submitPass(task());

        task.swallowSubmitResponse(false);
        task.supportsListActive(true);
        maintenanceService.maintain(task());

        SyncRemoteJob job = onlyJob();
        assertThat(job.getState()).isEqualTo(RemoteJobState.SUBMITTED);
        assertThat(job.getExternalHandle()).isEqualTo("job-1");
        assertThat(task.remoteJobs()).hasSize(1);
    }

    /**
     * When the partner cannot be asked, assume-submitted holds the slot and does not resubmit. Being
     * wrong costs a wasted slot and a late sync; the other reading costs a duplicate billable job.
     */
    @Test
    void anUnresolvableAmbiguousSubmitIsOrphanedAndItsSlotStaysHeld() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        task.swallowSubmitResponse(true);
        submitService.submitPass(task());
        java.util.UUID leaseId = onlyJob().getQuotaLeaseId();

        maintenanceService.maintain(task());

        assertThat(onlyJob().getState()).isEqualTo(RemoteJobState.ORPHANED);
        assertThat(leases.findById(leaseId)).isPresent();
    }

    @Test
    void anOrphanedJobStillCoversItsDemandSoNothingIsResubmitted() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        task.swallowSubmitResponse(true);
        submitService.submitPass(task());
        maintenanceService.maintain(task());
        task.swallowSubmitResponse(false);

        assertThat(submitService.submitPass(task())).isZero();
        assertThat(jobs.findAll()).hasSize(1);
    }

    /**
     * Adopt, never restart: a job whose owner has been replaced is claimed by whoever polls next, and
     * the remote job keeps running under new management.
     */
    @Test
    void aJobWhoseOwnerIsGoneIsAdoptedByThePollPassRatherThanResubmitted() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        submitService.submitPass(task());
        transactions.executeWithoutResult(status -> {
            SyncRemoteJob job = onlyJob();
            job.setOwnerInstance("an-instance-that-died");
            jobs.save(job);
        });

        task.reports(JobStatus.running());
        makeJobsDue();
        assertThat(pollService.pollPass(task())).isEqualTo(1);

        assertThat(onlyJob().getOwnerInstance()).isNotEqualTo("an-instance-that-died");
        assertThat(jobs.findAll()).hasSize(1);
    }

    @Test
    void aJobThePartnerReportsAsFailedIsSettledAndItsSlotReleased() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        submitService.submitPass(task());
        java.util.UUID leaseId = onlyJob().getQuotaLeaseId();

        task.reports(JobStatus.failed("the export blew up"));
        makeJobsDue();
        pollService.pollPass(task());

        assertThat(onlyJob().getState()).isEqualTo(RemoteJobState.FAILED);
        assertThat(leases.findById(leaseId)).isEmpty();
    }

    /** A settled job stops covering its demand, which is what "requeue" means here. */
    @Test
    void theDemandOfAFailedJobBecomesEligibleAgain() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        submitService.submitPass(task());
        task.reports(JobStatus.failed("the export blew up"));
        makeJobsDue();
        pollService.pollPass(task());

        assertThat(submitService.submitPass(task())).isEqualTo(1);
    }

    /** A job past its lifetime is cancelled at the partner, settled, and gives its slot back. */
    @Test
    void aJobPastItsLifetimeIsCancelledExpiredAndReleased() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        submitService.submitPass(task());
        java.util.UUID leaseId = onlyJob().getQuotaLeaseId();
        transactions.executeWithoutResult(status -> {
            SyncRemoteJob job = onlyJob();
            job.setExpiresAt(Instant.now().minusSeconds(1));
            jobs.save(job);
        });

        maintenanceService.maintain(task());

        assertThat(onlyJob().getState()).isEqualTo(RemoteJobState.EXPIRED);
        assertThat(task.cancelled()).containsExactly("job-1");
        assertThat(leases.findById(leaseId)).isEmpty();
    }

    /** A partner that has forgotten a job says so, and that is not the same as a failure. */
    @Test
    void aJobThePartnerHasForgottenIsExpiredRatherThanFailed() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        submitService.submitPass(task());
        task.reset();

        makeJobsDue();
        pollService.pollPass(task());

        assertThat(onlyJob().getState()).isEqualTo(RemoteJobState.EXPIRED);
    }

    @Test
    void theQuotaLimitsHowManyJobsAreInFlightAtOnce() {
        givenOrder("A-1");
        givenOrder("A-2");
        givenOrder("A-3");
        partner.holds("A-1", "PAID", CHANGED_AT).holds("A-2", "PAID", CHANGED_AT)
                .holds("A-3", "PAID", CHANGED_AT);

        // submit-batch-size is 10, so all three keys go in one job; two passes over fresh demand would
        // still be capped at the quota's two slots.
        submitService.submitPass(task());

        assertThat(leases.findAll().size()).isLessThanOrEqualTo(2);
    }

    @Test
    void aStillRunningJobIsProbedAgainLaterRatherThanSettled() {
        givenOrder("A-1");
        partner.holds("A-1", "PAID", CHANGED_AT);
        submitService.submitPass(task());

        task.reports(JobStatus.running());
        makeJobsDue();
        pollService.pollPass(task());

        SyncRemoteJob job = onlyJob();
        assertThat(job.getState()).isEqualTo(RemoteJobState.RUNNING);
        assertThat(job.getPollAttempts()).isEqualTo(1);
        assertThat(job.getNextPollAt()).isAfter(Instant.now());
    }

    private RegisteredTask<?, ?, ?> task() {
        return registry.find(TASK).orElseThrow();
    }

    /**
     * Brings every in-flight job due to be probed now.
     *
     * <p>A freshly submitted job's first probe is a whole poll interval away, which is right in
     * production and unhelpful in a test that wants to drive one pass at a time. Moving the due time
     * back is how the test says "a poll interval has elapsed" without waiting one or shortening the
     * configured interval, which would let the background scheduler interfere with the assertions.
     */
    private void makeJobsDue() {
        transactions.executeWithoutResult(status -> jobs.findAll().forEach(job -> {
            job.setNextPollAt(Instant.now().minusSeconds(1));
            jobs.save(job);
        }));
    }

    private SyncRemoteJob onlyJob() {
        List<SyncRemoteJob> all = jobs.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private void givenOrder(String externalId) {
        transactions.executeWithoutResult(status -> {
            LocalOrder order = new LocalOrder();
            order.setExternalId(externalId);
            order.setStatus("PENDING");
            orders.save(order);
        });
    }

    private LocalOrder order(String externalId) {
        return orders.findByExternalId(externalId).orElseThrow();
    }
}
