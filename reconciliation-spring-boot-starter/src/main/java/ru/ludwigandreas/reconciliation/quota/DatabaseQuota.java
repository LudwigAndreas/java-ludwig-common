package ru.ludwigandreas.reconciliation.quota;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditLogger;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.entity.QuotaLease;
import ru.ludwigandreas.reconciliation.entity.QuotaWaiter;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.QuotaWaiterRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The quota, backed by {@code sync_quota_lease} and {@code sync_quota_waiter}.
 *
 * <h2>How an acquisition is made atomic</h2>
 *
 * <p>Counting live slots and then inserting one is a read-modify-write, and two instances doing it at
 * the same instant would both see room and both take it - which is precisely the thing a quota is
 * supposed to prevent, happening in the one place it is hardest to notice. The whole acquisition
 * therefore runs under a Postgres transaction-scoped advisory lock keyed on the quota name.
 *
 * <p>An advisory lock rather than a lock table: there is no row that naturally represents "the quota"
 * - quota limits are configuration, not data - and inventing one only to lock it means a table whose
 * sole purpose is to be locked, plus the insert-if-absent dance that goes with it. The lock is
 * transaction-scoped, so it is released by commit or rollback with no path that can leak it.
 *
 * <h2>Why every operation uses an explicit {@code TransactionTemplate}</h2>
 *
 * <p>Because two of them - renewing and releasing - are called on a handle, and a handle is not a
 * Spring bean. {@code @Transactional} is applied by a proxy, and nothing proxies an inner class
 * handed out to a caller, so the annotation there would be decoration: the renewal would silently
 * join whatever transaction the caller happened to be in, or none. A template does the same job with
 * no proxy involved, and says what the propagation is at the point it matters.
 *
 * <p>The propagation is {@code REQUIRES_NEW} throughout, and that is load-bearing for acquisition: a
 * slot taken inside the caller's transaction is invisible to every other instance until that
 * transaction commits - which, for a submit pass, is after the partner has already been called. The
 * count that was supposed to gate the call would be updated only once the call could no longer be
 * taken back.
 *
 * <h2>The three rules that make leases safe</h2>
 *
 * <ol>
 *   <li><b>Heartbeat, never trust liveness.</b> A slot is held only while its expiry is in the future.
 *       Without that, one crash permanently shrinks the quota by one, and after enough crashes the
 *       integration stops entirely - with no error anywhere, because from the inside it is
 *       indistinguishable from a busy partner.</li>
 *   <li><b>Verify before reclaim.</b> An expired lease does not mean the remote work stopped. The
 *       reclaim sweep consults the partner first under the default policy; see
 *       {@code QuotaReclaimService}.</li>
 *   <li><b>Quota first, then rate-limiter permit.</b> Enforced by the callers, and it matters: taking
 *       a permit for work that is then not allowed to start burns the partner's rate budget on
 *       nothing.</li>
 * </ol>
 */
public class DatabaseQuota implements Quota {

    /** Bounded page for the queue read: fairness only needs to know who is first. */
    private static final PageRequest QUEUE_HEAD = PageRequest.of(0, 1);

    /**
     * How many heartbeat intervals a waiter may miss before its queue entry is ignored.
     *
     * <p>Under FIFO, a dead instance at the head of the queue stops the quota granting anything at
     * all, so the liveness rule that applies to leases has to apply to the queue as well. Three
     * intervals is the same margin the platform uses elsewhere: enough for a garbage-collection pause
     * or a slow database, short enough that a genuinely dead waiter is out of the way quickly.
     */
    private static final int WAITER_LIVENESS_INTERVALS = 3;

    /**
     * How long to pause between attempts when a caller has asked to wait for a slot.
     *
     * <p>Polling rather than a database notification: an acquisition that waits is already the
     * unusual case - the default is not to wait at all - and a LISTEN/NOTIFY channel would add a
     * dedicated connection and a second failure mode to a path whose whole point is to give up
     * quickly.
     */
    private static final Duration ACQUIRE_POLL_INTERVAL = Duration.ofMillis(200);

    /** Milliseconds per second, for the gauge that reports a wait in seconds. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    private static final Logger log = LoggerFactory.getLogger(DatabaseQuota.class);

    private final Map<String, ReconciliationProperties.Quota> configured;
    private final QuotaLeaseRepository leases;
    private final QuotaWaiterRepository waiters;
    private final EntityManager entityManager;
    private final ReconciliationMetrics metrics;
    private final ReconciliationAuditLogger auditLogger;
    private final String owner;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate readOnly;

    /**
     * Creates the quota.
     *
     * @param configured    the configured quotas, by name
     * @param leases        the lease table
     * @param waiters       the queue table
     * @param entityManager used for the advisory lock
     * @param metrics       instrumentation
     * @param auditLogger   the audit trail
     * @param owner         this instance's identity
     * @param transactionManager the transaction manager the templates run against
     */
    public DatabaseQuota(Map<String, ReconciliationProperties.Quota> configured,
                         QuotaLeaseRepository leases,
                         QuotaWaiterRepository waiters,
                         EntityManager entityManager,
                         ReconciliationMetrics metrics,
                         ReconciliationAuditLogger auditLogger,
                         String owner,
                         PlatformTransactionManager transactionManager) {
        this.configured = Map.copyOf(configured);
        this.leases = leases;
        this.waiters = waiters;
        this.entityManager = entityManager;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.owner = owner;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<QuotaLeaseHandle> tryAcquire(String quotaName, String taskName, UUID jobId) {
        ReconciliationProperties.Quota settings = configured.get(quotaName);
        if (settings == null) {
            // Unreachable in a validated context; see RateLimitRegistry for the same reasoning.
            return Optional.empty();
        }
        Instant started = Instant.now();
        Instant deadline = started.plus(settings.getAcquireTimeout());
        Optional<QuotaLeaseHandle> acquired = attempt(quotaName, taskName, jobId, settings);

        // With the default acquire-timeout of zero this loop never runs, which is the intended shape:
        // a scheduled pass that cannot get a slot has nothing useful to do but come back next tick.
        // A caller that has explicitly asked to wait is usually one whose surrounding work has already
        // cost something - a rate-limiter permit, a partially built request - and for which giving up
        // a few hundred milliseconds early is the more expensive answer.
        while (acquired.isEmpty() && Instant.now().isBefore(deadline)) {
            if (!pause()) {
                break;
            }
            acquired = attempt(quotaName, taskName, jobId, settings);
        }

        metrics.recordQuotaAcquire(quotaName, taskName, acquired.isPresent(),
                Duration.between(started, Instant.now()));
        return acquired;
    }

    private Optional<QuotaLeaseHandle> attempt(String quotaName,
                                               String taskName,
                                               UUID jobId,
                                               ReconciliationProperties.Quota settings) {
        return requiresNew.execute(status -> {
            lockQuota(quotaName);
            QuotaWaiter waiter = enqueue(quotaName, taskName);
            return grantIfPossible(quotaName, taskName, jobId, settings, waiter);
        });
    }

    /**
     * Waits one poll interval.
     *
     * @return whether the wait completed; false if the thread was interrupted, in which case the
     *         caller must stop waiting rather than swallow the interrupt and carry on
     */
    private static boolean pause() {
        try {
            Thread.sleep(ACQUIRE_POLL_INTERVAL.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Optional<QuotaLeaseHandle> grantIfPossible(String quotaName,
                                                       String taskName,
                                                       UUID jobId,
                                                       ReconciliationProperties.Quota settings,
                                                       QuotaWaiter waiter) {
        Instant now = Instant.now();
        long live = leases.countByQuotaNameAndExpiresAtGreaterThan(quotaName, now);
        if (live >= settings.getMaxConcurrent()) {
            return Optional.empty();
        }
        if (settings.isFifo() && !isHeadOfQueue(quotaName, settings, waiter, now)) {
            // Somebody has been waiting longer. Staying enqueued is what makes the position mean
            // something across ticks: the next attempt inherits this one's place rather than starting
            // at the back, which is what lets a five-minute task ever beat a thirty-second one.
            return Optional.empty();
        }

        QuotaLease lease = new QuotaLease();
        lease.setQuotaName(quotaName);
        lease.setTaskName(taskName);
        lease.setOwnerInstance(owner);
        lease.setJobId(jobId);
        lease.setAcquiredAt(now);
        lease.setHeartbeatAt(now);
        lease.setExpiresAt(now.plus(settings.getLeaseTtl()));
        lease.setMaxLifetimeAt(now.plus(settings.getMaxLifetime()));
        QuotaLease saved = leases.save(lease);
        waiters.delete(waiter);

        auditLogger.record(AuditEvent.builder(taskName, AuditEvent.Category.LEASE, "lease.acquired")
                .subject(quotaName).detail("slot " + (live + 1) + " of " + settings.getMaxConcurrent())
                .build());
        return Optional.of(new Handle(saved.getId(), quotaName, taskName, settings));
    }

    private boolean isHeadOfQueue(String quotaName,
                                  ReconciliationProperties.Quota settings,
                                  QuotaWaiter waiter,
                                  Instant now) {
        Instant aliveAfter = now.minus(settings.getHeartbeatInterval().multipliedBy(WAITER_LIVENESS_INTERVALS));
        List<QuotaWaiter> head = waiters.findLiveQueue(quotaName, aliveAfter, QUEUE_HEAD);
        return head.isEmpty() || head.get(0).getId().equals(waiter.getId());
    }

    private QuotaWaiter enqueue(String quotaName, String taskName) {
        Optional<QuotaWaiter> existing =
                waiters.findByQuotaNameAndTaskNameAndOwnerInstance(quotaName, taskName, owner);
        if (existing.isPresent()) {
            QuotaWaiter waiter = existing.get();
            // enqueued_at is never refreshed: it is this task's position in the queue, and touching it
            // on every attempt would send the longest-waiting task to the back on each try.
            waiter.setHeartbeatAt(Instant.now());
            return waiters.save(waiter);
        }
        QuotaWaiter waiter = new QuotaWaiter();
        waiter.setQuotaName(quotaName);
        waiter.setTaskName(taskName);
        waiter.setOwnerInstance(owner);
        waiter.setEnqueuedAt(Instant.now());
        waiter.setHeartbeatAt(Instant.now());
        return waiters.save(waiter);
    }

    @Override
    public Optional<QuotaLeaseHandle> adopt(UUID leaseId) {
        return requiresNew.execute(status -> leases.findById(leaseId)
                .filter(lease -> lease.getExpiresAt().isAfter(Instant.now()))
                .map(lease -> {
                    lease.setOwnerInstance(owner);
                    lease.setHeartbeatAt(Instant.now());
                    leases.save(lease);
                    ReconciliationProperties.Quota settings = configured.get(lease.getQuotaName());
                    log.info("Adopted quota lease {} on '{}' from a previous owner",
                            leaseId, lease.getQuotaName());
                    return (QuotaLeaseHandle) new Handle(
                            lease.getId(), lease.getQuotaName(), lease.getTaskName(), settings);
                }));
    }

    @Override
    public long inFlight(String quotaName) {
        return readOnly.execute(status ->
                leases.countByQuotaNameAndExpiresAtGreaterThan(quotaName, Instant.now()));
    }

    @Override
    public double oldestWaiterAgeSeconds(String quotaName) {
        ReconciliationProperties.Quota settings = configured.get(quotaName);
        if (settings == null) {
            return 0.0;
        }
        Instant aliveAfter = Instant.now()
                .minus(settings.getHeartbeatInterval().multipliedBy(WAITER_LIVENESS_INTERVALS));
        Instant oldest = readOnly.execute(status -> waiters.findOldestEnqueuedAt(quotaName, aliveAfter));
        return oldest == null ? 0.0 : Duration.between(oldest, Instant.now()).toMillis() / MILLIS_PER_SECOND;
    }

    /** The configured limit, for the saturation gauge and the actuator endpoint. */
    public int limit(String quotaName) {
        ReconciliationProperties.Quota settings = configured.get(quotaName);
        return settings == null ? 0 : settings.getMaxConcurrent();
    }

    /** Every configured quota name. */
    public java.util.Set<String> names() {
        return configured.keySet();
    }

    /**
     * Takes the advisory lock that serializes acquisitions of one quota.
     *
     * <p>{@code hashtext} maps the name to the {@code bigint} the lock API takes. A hash collision
     * between two quota names would serialize two unrelated quotas against each other - slower, never
     * incorrect - which is the right way round for this trade.
     */
    private void lockQuota(String quotaName) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:name))")
                .setParameter("name", "ludwig.reconciliation.quota:" + quotaName)
                .getSingleResult();
    }

    /** A held slot. */
    private final class Handle implements QuotaLeaseHandle {

        private final UUID leaseId;
        private final String quotaName;
        private final String taskName;
        private final ReconciliationProperties.Quota settings;
        private volatile boolean released;

        private Handle(UUID leaseId, String quotaName, String taskName,
                       ReconciliationProperties.Quota settings) {
            this.leaseId = leaseId;
            this.quotaName = quotaName;
            this.taskName = taskName;
            this.settings = settings;
        }

        @Override
        public UUID leaseId() {
            return leaseId;
        }

        @Override
        public String quotaName() {
            return quotaName;
        }

        @Override
        public boolean renew() {
            if (released) {
                return false;
            }
            Instant now = Instant.now();
            boolean held = Boolean.TRUE.equals(requiresNew.execute(status ->
                    leases.renew(leaseId, owner, now, now.plus(settings.getLeaseTtl())) == 1));
            if (!held) {
                log.warn("Quota lease {} on '{}' was lost by {}; another instance may already have "
                        + "taken the slot", leaseId, quotaName, owner);
            }
            return held;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            requiresNew.executeWithoutResult(status -> leases.deleteById(leaseId));
            auditLogger.record(AuditEvent.builder(taskName, AuditEvent.Category.LEASE, "lease.released")
                    .subject(quotaName).build());
        }
    }
}
