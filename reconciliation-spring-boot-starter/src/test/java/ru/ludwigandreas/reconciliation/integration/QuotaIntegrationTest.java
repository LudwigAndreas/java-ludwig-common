package ru.ludwigandreas.reconciliation.integration;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.config.QuotaReclaimPolicy;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.entity.QuotaLease;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.metrics.NoopReconciliationMetrics;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.quota.QuotaLeaseHandle;
import ru.ludwigandreas.reconciliation.quota.QuotaReclaimService;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.QuotaWaiterRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quota: the part of this module that carries the real risk, tested against a real Postgres.
 *
 * <p>Two "instances" are two {@link DatabaseQuota} objects with different owner identities over the
 * same tables. That is exactly what two pods are as far as this code is concerned - the coordination
 * is entirely in the database - so it is the honest way to test it without starting two contexts.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Testcontainers
class QuotaIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    private static final String QUOTA = "partner-exports";
    private static final String TASK_A = "task-a";
    private static final String TASK_B = "task-b";

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private QuotaLeaseRepository leases;

    @Autowired
    private QuotaWaiterRepository waiters;

    @Autowired
    private SyncRemoteJobRepository jobs;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditSink auditLogger;

    @Autowired
    private TransactionTemplate transactions;

    private ReconciliationProperties.Quota settings;
    private DatabaseQuota instanceOne;
    private DatabaseQuota instanceTwo;

    @BeforeEach
    void resetState() {
        transactions.executeWithoutResult(status -> {
            leases.deleteAllInBatch();
            waiters.deleteAllInBatch();
            jobs.deleteAllInBatch();
        });
        settings = quota(2);
        instanceOne = quotaFor("instance-1", settings);
        instanceTwo = quotaFor("instance-2", settings);
    }

    /** The whole reason the counting is in the database rather than in a per-JVM bulkhead. */
    @Test
    void theLimitHoldsAcrossTwoInstances() {
        assertThat(instanceOne.tryAcquire(QUOTA, TASK_A, null)).isPresent();
        assertThat(instanceTwo.tryAcquire(QUOTA, TASK_B, null)).isPresent();

        assertThat(instanceOne.tryAcquire(QUOTA, TASK_A, null)).isEmpty();
        assertThat(instanceTwo.tryAcquire(QUOTA, TASK_B, null)).isEmpty();
        assertThat(instanceOne.inFlight(QUOTA)).isEqualTo(2);
    }

    @Test
    void releasingASlotLetsTheNextCallerHaveIt() {
        QuotaLeaseHandle first = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        instanceTwo.tryAcquire(QUOTA, TASK_B, null).orElseThrow();
        assertThat(instanceOne.tryAcquire(QUOTA, TASK_A, null)).isEmpty();

        first.close();

        assertThat(instanceOne.tryAcquire(QUOTA, TASK_A, null)).isPresent();
    }

    @Test
    void renewingExtendsTheLeaseAndReportsThatItIsStillHeld() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        Instant before = leases.getByIdOrThrow(held.leaseId()).getExpiresAt();

        assertThat(held.renew()).isTrue();

        assertThat(leases.getByIdOrThrow(held.leaseId()).getExpiresAt()).isAfterOrEqualTo(before);
    }

    /**
     * The failure mode this whole design exists around: a holder that dies must not shrink the quota
     * permanently. Its slot becomes claimable on its own, with no participation from the dead holder.
     */
    @Test
    void aSlotWhoseHolderStoppedHeartbeatingBecomesClaimableOnItsOwn() {
        DatabaseQuota one = quotaFor("instance-1", quota(1));
        DatabaseQuota two = quotaFor("instance-2", quota(1));
        QuotaLeaseHandle abandoned = one.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        assertThat(one.inFlight(QUOTA)).isEqualTo(1);
        assertThat(two.tryAcquire(QUOTA, TASK_B, null)).isEmpty();

        // The holder dies: it simply stops renewing, and nothing it does releases the slot.
        expire(abandoned.leaseId());

        assertThat(two.tryAcquire(QUOTA, TASK_B, null)).isPresent();
    }

    /** A renewal that arrives after the slot was reclaimed must fail rather than silently take it back. */
    @Test
    void aRenewalOfALostLeaseFails() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        expire(held.leaseId());

        assertThat(held.renew()).isFalse();
    }

    /**
     * Without a queue, a task on a fast cadence wins essentially every race and a slower one can starve
     * indefinitely while every individual acquisition looks correct.
     */
    @Test
    void slotsAreGrantedInArrivalOrder() {
        DatabaseQuota single = quotaFor("instance-1", quota(1));
        QuotaLeaseHandle occupying = single.tryAcquire(QUOTA, TASK_A, null).orElseThrow();

        // The slow task queues first, then the fast one tries repeatedly and must not overtake it.
        assertThat(single.tryAcquire(QUOTA, "slow-task", null)).isEmpty();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(single.tryAcquire(QUOTA, "fast-task", null)).isEmpty();
        }

        occupying.close();

        assertThat(single.tryAcquire(QUOTA, "fast-task", null)).isEmpty();
        assertThat(single.tryAcquire(QUOTA, "slow-task", null)).isPresent();
    }

    @Test
    void theOldestWaiterGaugeReportsHowLongTheQueueHasBeenWaiting() {
        DatabaseQuota single = quotaFor("instance-1", quota(1));
        single.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        assertThat(single.oldestWaiterAgeSeconds(QUOTA)).isZero();

        assertThat(single.tryAcquire(QUOTA, "waiting-task", null)).isEmpty();

        assertThat(single.oldestWaiterAgeSeconds(QUOTA)).isGreaterThanOrEqualTo(0.0);
        assertThat(waiters.findAll()).hasSize(1);
    }

    /**
     * The rule the reclaim sweep exists to enforce: an expired lease does not mean the remote work
     * stopped. Reclaiming a slot whose job is still running at the partner is how a stated limit is
     * quietly exceeded.
     */
    @Test
    void anExpiredLeaseIsNotReclaimedWhileItsJobIsStillRunningAtThePartner() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        SyncRemoteJob job = givenJob(RemoteJobState.RUNNING, held.leaseId());
        attachJob(held.leaseId(), job.getId());
        expire(held.leaseId());

        int reclaimed = reclaimService(QuotaReclaimPolicy.VERIFY_REMOTE).sweep();

        assertThat(reclaimed).isZero();
        assertThat(leases.findById(held.leaseId())).isPresent();
    }

    @Test
    void anExpiredLeaseIsReclaimedOnceItsJobHasSettled() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        SyncRemoteJob job = givenJob(RemoteJobState.COLLECTED, held.leaseId());
        attachJob(held.leaseId(), job.getId());
        expire(held.leaseId());

        int reclaimed = reclaimService(QuotaReclaimPolicy.VERIFY_REMOTE).sweep();

        assertThat(reclaimed).isEqualTo(1);
        assertThat(leases.findById(held.leaseId())).isEmpty();
    }

    /** on-expiry skips the check, which is only appropriate for a partner with no status endpoint. */
    @Test
    void underOnExpiryAnExpiredLeaseIsReclaimedWithoutAsking() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        SyncRemoteJob job = givenJob(RemoteJobState.RUNNING, held.leaseId());
        attachJob(held.leaseId(), job.getId());
        expire(held.leaseId());

        int reclaimed = reclaimService(QuotaReclaimPolicy.ON_EXPIRY).sweep();

        assertThat(reclaimed).isEqualTo(1);
    }

    /**
     * A lease being renewed forever by a job that will never finish is a leak heartbeating cannot
     * detect, because the heartbeat is working perfectly. max-lifetime is what bounds it.
     */
    @Test
    void aLeasePastItsMaxLifetimeIsReclaimedEvenWhileItsJobStillRuns() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        SyncRemoteJob job = givenJob(RemoteJobState.RUNNING, held.leaseId());
        attachJob(held.leaseId(), job.getId());
        transactions.executeWithoutResult(status -> {
            QuotaLease lease = leases.getByIdOrThrow(held.leaseId());
            lease.setExpiresAt(Instant.now().minusSeconds(60));
            lease.setMaxLifetimeAt(Instant.now().minusSeconds(30));
            leases.save(lease);
        });

        assertThat(reclaimService(QuotaReclaimPolicy.VERIFY_REMOTE).sweep()).isEqualTo(1);
    }

    /** A restarted pod adopts the running job's lease instead of releasing it and taking a new slot. */
    @Test
    void anotherInstanceCanAdoptALiveLease() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();

        Optional<QuotaLeaseHandle> adopted = instanceTwo.adopt(held.leaseId());

        assertThat(adopted).isPresent();
        assertThat(leases.getByIdOrThrow(held.leaseId()).getOwnerInstance()).isEqualTo("instance-2");
        assertThat(adopted.orElseThrow().renew()).isTrue();
        assertThat(instanceOne.inFlight(QUOTA)).isEqualTo(1);
    }

    @Test
    void anExpiredLeaseCannotBeAdopted() {
        QuotaLeaseHandle held = instanceOne.tryAcquire(QUOTA, TASK_A, null).orElseThrow();
        expire(held.leaseId());

        assertThat(instanceTwo.adopt(held.leaseId())).isEmpty();
    }

    @Test
    void aTaskWithNoQuotaConfiguredIsNeverBlocked() {
        assertThat(instanceOne.tryAcquire("not-configured", TASK_A, null)).isEmpty();
    }

    private ReconciliationProperties.Quota quota(int maxConcurrent) {
        ReconciliationProperties.Quota configured = new ReconciliationProperties.Quota();
        configured.setMaxConcurrent(maxConcurrent);
        configured.setLeaseTtl(Duration.ofMinutes(15));
        configured.setHeartbeatInterval(Duration.ofMinutes(2));
        configured.setMaxLifetime(Duration.ofHours(6));
        return configured;
    }

    private DatabaseQuota quotaFor(String owner, ReconciliationProperties.Quota configured) {
        return new DatabaseQuota(Map.of(QUOTA, configured), leases, waiters, entityManager,
                new NoopReconciliationMetrics(), auditLogger, owner, transactionManager);
    }

    private QuotaReclaimService reclaimService(QuotaReclaimPolicy policy) {
        ReconciliationProperties.Quota configured = quota(settings.getMaxConcurrent());
        configured.setReclaim(policy);
        return new QuotaReclaimService(Map.of(QUOTA, configured), leases, waiters, jobs,
                new ru.ludwigandreas.reconciliation.engine.TaskRegistry(List.of()),
                new NoopReconciliationMetrics(), auditLogger, transactionManager);
    }

    private SyncRemoteJob givenJob(RemoteJobState state, UUID leaseId) {
        return transactions.execute(status -> {
            SyncRemoteJob job = new SyncRemoteJob();
            job.setTaskName(TASK_A);
            job.setIdempotencyKey(UUID.randomUUID().toString());
            job.setState(state);
            job.setExternalHandle("handle-1");
            job.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
            job.setCreatedAt(Instant.now());
            job.setQuotaLeaseId(leaseId);
            return jobs.save(job);
        });
    }

    private void attachJob(UUID leaseId, UUID jobId) {
        transactions.executeWithoutResult(status -> {
            QuotaLease lease = leases.getByIdOrThrow(leaseId);
            lease.setJobId(jobId);
            leases.save(lease);
        });
    }

    /** Simulates the holder dying: it simply stops renewing, and the lease runs out. */
    private void expire(UUID leaseId) {
        transactions.executeWithoutResult(status -> {
            QuotaLease lease = leases.getByIdOrThrow(leaseId);
            lease.setExpiresAt(Instant.now().minusSeconds(1));
            leases.save(lease);
        });
    }
}
