package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.DistributedLockRepository;
import ru.ludwigandreas.notification.repository.IdempotencyRecordRepository;
import ru.ludwigandreas.notification.repository.NotificationRequestRepository;
import ru.ludwigandreas.notification.repository.RateLimitWindowRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryPriority;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationRequestEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationSource;
import ru.ludwigandreas.notification.repository.entity.RequestStatus;
import ru.ludwigandreas.notification.service.idempotency.IdempotencyStore;
import ru.ludwigandreas.notification.service.lock.DistributedLock;
import ru.ludwigandreas.notification.service.lock.LockLeaseService;
import ru.ludwigandreas.notification.service.lock.PostgresDistributedLock;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.queue.ChannelRateLimiter;
import ru.ludwigandreas.notification.service.queue.DeliveryClaimService;
import ru.ludwigandreas.notification.service.metrics.NoopNotificationMetrics;

/**
 * The properties that only exist under concurrency, against a real PostgreSQL.
 *
 * <p>Multi-replica safety is a hard requirement of this service and it is the one thing a
 * single-threaded test can never demonstrate: every mechanism here - {@code SKIP LOCKED}, the leased
 * lock, the conditional upsert, the shared rate-limit counter - is correct by construction only
 * because of how PostgreSQL behaves when two transactions meet on a row. An H2 or a mock would pass
 * all of this and prove nothing.
 *
 * <p>Threads stand in for replicas. That is a faithful model here because nothing in these mechanisms
 * is per-process: the coordination is entirely in the database, so two threads holding two
 * connections are indistinguishable from two pods holding two connections.
 */
@org.springframework.test.context.TestPropertySource(properties = {
        // Small and explicit, so the parallel reservation test is decisive rather than probabilistic.
        // The channel itself stays off - the limiter is consulted before a channel bean is involved,
        // which is also why a rate limit can be lowered during an incident without a redeploy.
        "ludwig.notification.channels.chat.max-per-window=4"
})
class DeliveryQueueConcurrencyTest extends NotificationTestBase {

    private static final int REPLICAS = 3;
    private static final int DELIVERIES = 60;

    /** Mirrors the property above; the limiter has no accessor and none is worth adding. */
    private static final int CHAT_RATE_LIMIT = 4;

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private NotificationDeliveryRepository deliveries;

    @Autowired
    private NotificationRequestRepository requests;

    @Autowired
    private DeliveryStatusHistoryRepository history;

    @Autowired
    private IdempotencyStore idempotencyStore;

    @Autowired
    private LockLeaseService lockLeases;

    @Autowired
    private ChannelRateLimiter rateLimiter;

    @Autowired
    private DistributedLockRepository locks;

    @Autowired
    private RateLimitWindowRepository rateLimitWindows;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecords;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID requestId;

    /**
     * Everything these tests touch is shared, cluster-wide state, which is the whole point of it -
     * and it means a leftover row is not a tidy-up detail but a test that passes or fails depending
     * on what ran before it. A lock left held by an earlier case blocks the next one; a rate-limit
     * window from an earlier case has already spent the budget the next one is measuring.
     */
    @BeforeEach
    void resetState() {
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
        locks.deleteAll();
        rateLimitWindows.deleteAll();
        idempotencyRecords.deleteAll();
        requestId = transactionTemplate.execute(status -> requests.saveAndFlush(request()).getId());
    }

    /**
     * The central claim of the design: three pollers take disjoint batches without blocking each
     * other and without coordination. If {@code SKIP LOCKED} were missing or the claim were split
     * into a select and an update, two replicas would claim the same delivery and it would be sent
     * twice - the failure this whole service is built to avoid.
     */
    @Test
    @DisplayName("three replicas claiming at once take disjoint batches and no row twice")
    void concurrentClaimsAreDisjoint() throws Exception {
        givenPending(DELIVERIES, DeliveryPriority.NORMAL);

        // Each replica asks for its share rather than for the whole queue. With a batch size equal
        // to the backlog, whichever replica reached the statement first would legitimately take
        // everything - correct, but it would prove disjointness without proving that SKIP LOCKED
        // lets the others through rather than blocking them.
        int perReplica = DELIVERIES / REPLICAS;
        List<List<UUID>> claimed = inParallel(REPLICAS, replica -> claimService
                .claim(ChannelType.EMAIL, DeliveryPriority.BULK, perReplica, Instant.now(),
                        "replica-" + replica)
                .stream()
                .map(NotificationDeliveryEntity::getId)
                .toList());

        List<UUID> all = claimed.stream().flatMap(List::stream).toList();
        assertThat(all).as("every claimed delivery is claimed exactly once").doesNotHaveDuplicates();
        assertThat(all).as("between them the replicas claimed the whole queue").hasSize(DELIVERIES);
        assertThat(claimed).as("no replica was blocked out entirely")
                .allSatisfy(batch -> assertThat(batch).hasSize(perReplica));

        assertThat(deliveries.findByRequestId(requestId))
                .allSatisfy(delivery -> {
                    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CLAIMED);
                    assertThat(delivery.getClaimedBy()).isNotNull();
                    assertThat(delivery.getClaimedAt()).isNotNull();
                });
    }

    /**
     * Ordering by priority alone is not enough: a bulk backlog is also, eventually, the oldest work.
     * The reserved pass is what guarantees a password reset a slot rather than hoping for one.
     */
    @Test
    @DisplayName("a reserved high-priority pass is served even behind a large bulk backlog")
    void highPriorityLaneIsNotStarved() {
        givenPending(100, DeliveryPriority.BULK);
        givenPending(5, DeliveryPriority.HIGH);

        List<NotificationDeliveryEntity> reserved = claimService.claim(
                ChannelType.EMAIL, DeliveryPriority.HIGH, 10, Instant.now(), "replica-1");

        assertThat(reserved).hasSize(5);
        assertThat(reserved).allSatisfy(delivery ->
                assertThat(delivery.getPriority()).isEqualTo(DeliveryPriority.HIGH));
    }

    @Test
    @DisplayName("the claim orders by priority, so HIGH is taken before NORMAL before BULK")
    void claimOrdersByPriority() {
        givenPending(5, DeliveryPriority.BULK);
        givenPending(5, DeliveryPriority.NORMAL);
        givenPending(5, DeliveryPriority.HIGH);

        List<NotificationDeliveryEntity> batch = claimService.claim(
                ChannelType.EMAIL, DeliveryPriority.BULK, 5, Instant.now(), "replica-1");

        // Stored as an orderable integer weight rather than the enum name: as a varchar this would
        // sort BULK first, silently inverting the lanes.
        assertThat(batch).allSatisfy(delivery ->
                assertThat(delivery.getPriority()).isEqualTo(DeliveryPriority.HIGH));
    }

    @Test
    @DisplayName("a delivery that is not yet due is not claimed")
    void schedulingIsHonoured() {
        NotificationDeliveryEntity future = pending(DeliveryPriority.HIGH);
        future.setNextAttemptAt(Instant.now().plus(Duration.ofHours(1)));
        save(future);

        assertThat(claimService.claim(ChannelType.EMAIL, DeliveryPriority.BULK, 10, Instant.now(),
                "replica-1")).isEmpty();
    }

    /**
     * Crash recovery. A pod killed between claiming and recording leaves rows CLAIMED forever, and
     * nobody finds out except the recipients who never heard from us.
     */
    @Test
    @DisplayName("a lease held by a dead instance is reclaimed, without burning an attempt")
    void staleLeasesAreReclaimed() {
        givenPending(3, DeliveryPriority.NORMAL);
        List<NotificationDeliveryEntity> claimed = claimService.claim(
                ChannelType.EMAIL, DeliveryPriority.BULK, 3, Instant.now(), "doomed-replica");
        assertThat(claimed).hasSize(3);

        // Nothing is reclaimed while the lease is live.
        assertThat(claimService.reclaimStale(Instant.now().minus(Duration.ofMinutes(10)))).isZero();

        // Once it has lapsed, every one comes back.
        assertThat(claimService.reclaimStale(Instant.now())).isEqualTo(3);

        assertThat(deliveries.findByRequestId(requestId)).allSatisfy(delivery -> {
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(delivery.getClaimedBy()).isNull();
            assertThat(delivery.getClaimedAt()).isNull();
            // A pod that died before dispatching made no attempt; counting one would spend a retry
            // on an infrastructure failure that has nothing to do with the recipient.
            assertThat(delivery.getAttempts()).isZero();
        });
    }

    /**
     * The shutdown drain. The sweeper would recover these anyway, but only after a full lease period -
     * so a rolling deploy would add that delay to every delivery in flight.
     */
    @Test
    @DisplayName("releasing a lease on shutdown returns only rows that are still CLAIMED")
    void shutdownDrainDoesNotResurrectSettledDeliveries() {
        givenPending(2, DeliveryPriority.NORMAL);
        List<NotificationDeliveryEntity> claimed = claimService.claim(
                ChannelType.EMAIL, DeliveryPriority.BULK, 2, Instant.now(), "leaving-replica");

        // One of them was settled in the same cycle, between the claim and the drain.
        NotificationDeliveryEntity settled = deliveries.getByIdOrThrow(claimed.get(0).getId());
        settled.setStatus(DeliveryStatus.SENT);
        settled.setSettledAt(Instant.now());
        save(settled);

        long released = claimService.release(
                claimed.stream().map(NotificationDeliveryEntity::getId).toList());

        assertThat(released).as("only the one still CLAIMED is handed back").isEqualTo(1);
        assertThat(deliveries.getByIdOrThrow(claimed.get(0).getId()).getStatus())
                .as("a delivery already sent must not be dragged back and sent twice")
                .isEqualTo(DeliveryStatus.SENT);
        assertThat(deliveries.getByIdOrThrow(claimed.get(1).getId()).getStatus())
                .isEqualTo(DeliveryStatus.PENDING);
    }

    /**
     * The first of the two platform gaps. Read-then-insert passes a single-threaded test and fails
     * exactly here: both replicas see "no row", both insert, and one takes a constraint violation
     * that aborts a transaction which has already written a request and its deliveries.
     */
    @Test
    @DisplayName("three replicas claiming one idempotency key agree on a single winner")
    void idempotencyClaimHasOneWinner() throws Exception {
        List<UUID> candidates = new ArrayList<>();
        for (int i = 0; i < REPLICAS; i++) {
            candidates.add(UUID.randomUUID());
        }

        List<UUID> owners = inParallel(REPLICAS,
                replica -> claimInTransaction("kafka", "shared-key", candidates.get(replica)));

        assertThat(owners).as("every replica is told the same owner").hasSize(REPLICAS)
                .containsOnly(owners.get(0));
        assertThat(candidates).as("and the owner is one of the contenders").contains(owners.get(0));
    }

    @Test
    @DisplayName("a second claim of the same key returns the first one's request, not a new one")
    void idempotencyClaimIsStable() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        UUID firstOwner = claimInTransaction("rest", "k", first);
        UUID secondOwner = claimInTransaction("rest", "k", second);

        assertThat(firstOwner).isEqualTo(first);
        assertThat(secondOwner).isEqualTo(first);
    }

    /**
     * Scoped by ingress, because a Kafka record key and an HTTP Idempotency-Key come from different
     * namespaces - and a collision between them would silently drop a genuine request.
     */
    @Test
    @DisplayName("the same key in two ingress scopes is two different claims")
    void idempotencyScopesAreSeparate() {
        UUID viaKafka = UUID.randomUUID();
        UUID viaRest = UUID.randomUUID();

        UUID kafkaOwner = claimInTransaction("kafka", "same", viaKafka);
        UUID restOwner = claimInTransaction("rest", "same", viaRest);

        assertThat(kafkaOwner).isEqualTo(viaKafka);
        assertThat(restOwner).isEqualTo(viaRest);
    }

    /**
     * The second platform gap. A digest run on three replicas sends three digests to one person, and
     * there is no SKIP LOCKED trick that fixes it - the work is defined over a set of rows rather
     * than over each row independently.
     */
    @Test
    @DisplayName("only one replica enters a job guarded by the distributed lock")
    void distributedLockAdmitsExactlyOne() throws Exception {
        AtomicInteger entered = new AtomicInteger();
        List<Boolean> acquired = inParallel(REPLICAS, replica -> {
            DistributedLock lock = new PostgresDistributedLock(lockLeases,
                    new NoopNotificationMetrics(), "replica-" + replica);
            return lock.runIfLockAvailable("test-job", handle -> {
                entered.incrementAndGet();
                sleepBriefly();
            });
        });

        assertThat(acquired).filteredOn(Boolean::booleanValue)
                .as("exactly one replica took the lock").hasSize(1);
        assertThat(entered).as("and exactly one body ran").hasValue(1);
    }

    @Test
    @DisplayName("the lock is released after the job, so the next run can take it")
    void distributedLockIsReleased() {
        DistributedLock first = new PostgresDistributedLock(lockLeases, new NoopNotificationMetrics(),
                "replica-1");
        DistributedLock second = new PostgresDistributedLock(lockLeases, new NoopNotificationMetrics(),
                "replica-2");

        assertThat(first.runIfLockAvailable("test-job", handle -> { })).isTrue();
        assertThat(second.runIfLockAvailable("test-job", handle -> { })).isTrue();
    }

    /**
     * A job that stalled long enough to lose its lock must not be able to renew it out from under
     * whichever replica has since taken over - a failed renewal is the signal to stop working.
     */
    @Test
    @DisplayName("a replica that no longer holds the lock cannot renew it")
    void renewalRequiresOwnership() {
        assertThat(lockLeases.tryAcquire("test-job", "replica-1")).isTrue();

        assertThat(lockLeases.renew("test-job", "replica-1")).isTrue();
        assertThat(lockLeases.renew("test-job", "replica-2")).isFalse();

        // A release naming the wrong owner does nothing, so the lock stays with replica-1 and
        // replica-2 still cannot take it.
        lockLeases.release("test-job", "replica-2");
        assertThat(lockLeases.tryAcquire("test-job", "replica-2")).isFalse();
    }

    /**
     * An in-process token bucket would be wrong here in the direction nobody notices: three pods each
     * holding a 100-per-window bucket send 300 per window, so the configured number would mean
     * nothing and would change meaning every time the deployment scaled.
     */
    @Test
    @DisplayName("three replicas together honour one rate limit, not one each")
    void rateLimitIsClusterWide() throws Exception {
        Instant now = Instant.now();
        List<Integer> grants = inParallel(REPLICAS, replica ->
                rateLimiter.reserve(ChannelType.CHAT, CHAT_RATE_LIMIT, now));

        assertThat(grants.stream().mapToInt(Integer::intValue).sum())
                .as("the sum across replicas never exceeds the configured limit")
                .isEqualTo(CHAT_RATE_LIMIT);
    }

    @Test
    @DisplayName("unused permits are returned, so a quiet channel does not burn its window")
    void unusedPermitsAreReturned() {
        Instant now = Instant.now();

        assertThat(rateLimiter.reserve(ChannelType.CHAT, CHAT_RATE_LIMIT, now))
                .isEqualTo(CHAT_RATE_LIMIT);
        assertThat(rateLimiter.reserve(ChannelType.CHAT, 1, now)).isZero();

        rateLimiter.release(ChannelType.CHAT, CHAT_RATE_LIMIT, now);
        assertThat(rateLimiter.reserve(ChannelType.CHAT, 1, now)).isEqualTo(1);
    }

    @Test
    @DisplayName("a channel with no configured limit is never throttled")
    void unlimitedChannelIsNotThrottled() {
        // 0 means unlimited, which is the right default for a provider inside the cluster - and the
        // limiter short-circuits before touching the database for it.
        assertThat(rateLimiter.reserve(ChannelType.EMAIL, 10_000, Instant.now())).isEqualTo(10_000);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private <T> List<T> inParallel(int workers, ParallelTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(workers);
        try {
            for (int i = 0; i < workers; i++) {
                int worker = i;
                futures.add(pool.submit((Callable<T>) () -> {
                    // Every worker waits on the same latch, so they contend rather than queue - which
                    // is the only arrangement that exercises what is being tested.
                    startLine.await();
                    return task.run(worker);
                }));
            }
            startLine.countDown();

            List<T> results = new ArrayList<>(workers);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The claim has to run inside a transaction: it is {@code MANDATORY}, because a claim that
     * committed on its own would leave a key reserved for a request whose transaction then rolled
     * back - and the retry of that request would be rejected as a duplicate of something that does
     * not exist.
     */
    private UUID claimInTransaction(String scope, String key, UUID requestUuid) {
        return transactionTemplate.execute(status -> idempotencyStore.claim(scope, key, requestUuid));
    }

    private void givenPending(int count, DeliveryPriority priority) {
        for (int i = 0; i < count; i++) {
            save(pending(priority));
        }
    }

    private NotificationDeliveryEntity pending(DeliveryPriority priority) {
        return NotificationDeliveryEntity.builder()
                .requestId(requestId)
                .channel(ChannelKind.EMAIL)
                .recipientUserId("user-" + UUID.randomUUID())
                .recipientAddress(UUID.randomUUID() + "@example.com")
                .recipientLocale("en")
                .recipientTimezone("UTC")
                .variables("{}")
                .templateKey("password-reset")
                .category("security")
                .categoryKind(CategoryKind.TRANSACTIONAL)
                .priority(priority)
                .status(DeliveryStatus.PENDING)
                .attempts(0)
                .maxAttempts(8)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .dedupKey(UUID.randomUUID().toString())
                .build();
    }

    private void save(NotificationDeliveryEntity delivery) {
        transactionTemplate.executeWithoutResult(status -> deliveries.saveAndFlush(delivery));
    }

    private NotificationRequestEntity request() {
        return NotificationRequestEntity.builder()
                .source(NotificationSource.REST)
                .templateKey("password-reset")
                .category("security")
                .categoryKind(CategoryKind.TRANSACTIONAL)
                .priority(DeliveryPriority.NORMAL)
                .variables("{}")
                .requestedRecipients("[]")
                .status(RequestStatus.FANNED_OUT)
                .build();
    }

    private static void sleepBriefly() {
        try {
            // Long enough that a second replica would be inside the body at the same time if the lock
            // were not doing its job.
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A unit of parallel work that may throw, which a plain {@code Function} cannot express. */
    @FunctionalInterface
    private interface ParallelTask<T> {
        T run(int worker) throws Exception;
    }
}
