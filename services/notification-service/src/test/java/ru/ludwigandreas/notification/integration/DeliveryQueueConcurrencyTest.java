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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
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
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.queue.ChannelRateLimiter;
import ru.ludwigandreas.notification.service.queue.DeliveryClaimService;

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

    private static final String TEST_LOCK = "test-job";

    /** Long enough that nothing here expires while a case is running. */
    private static final Duration LEASE = Duration.ofMinutes(5);

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
    private ChannelRateLimiter rateLimiter;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RateLimitWindowRepository rateLimitWindows;


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
        clearRunLocks();
        rateLimitWindows.deleteAll();
        requestId = transactionTemplate.execute(status -> requests.saveAndFlush(request()).getId());
    }

    /**
     * {@code job_run_lock} keeps its row when a lease is released - the owner is nulled and the
     * expiry moved to now - so an empty table is not the resting state and asserting one would be
     * asserting the wrong thing. What must not survive a case is a <em>live</em> lease, so the rows
     * are deleted outright. Raw JDBC because this table belongs to job-core: it maps no entity here
     * and there is no repository to call.
     */
    private void clearRunLocks() {
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM job_run_lock");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not clear job_run_lock between tests", e);
        }
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

    /*
     * The three idempotency-claim cases that used to sit here are gone, and not because they stopped
     * mattering: the mechanism is no longer this service's. IdempotencyStoreIT in
     * idempotency-spring-boot-starter now races N threads on one key, proves a rollback frees it, and pins
     * the TTL and lease boundaries - against the same PostgreSQL, with more cases than these had. What this
     * service still tests is its own contract: that a duplicate submit answers 200 with the original
     * request and duplicate=true, in NotificationLifecycleIntegrationTest and RestIngressIntegrationTest.
     *
     * Keeping copies here would mean two suites asserting one mechanism, which is how they start to
     * disagree - and the one in the module that owns the code is the one that would be updated.
     */

    /**
     * The fold's migration, on a database that never had the old table.
     *
     * <p>The master changelog includes {@code job-core}'s changelog and then drops
     * {@code notification_lock}, and the order is not cosmetic: an {@code <include>} contributes its
     * changesets where it appears, so the reverse order would leave a fresh database with neither lock
     * table between the two changesets. On a fresh database 0003 still creates {@code
     * notification_lock} and 0005 immediately drops it again - Liquibase history is append-only, so
     * an applied changeset is never edited - and this case is what says the pair leaves the right end
     * state rather than something that merely started up.
     *
     * <p>Every case in this class runs against a container built by exactly that changelog, so this
     * is the fresh-database path and not a reconstruction of it.
     */
    @Test
    @DisplayName("a fresh database ends with job_run_lock and without notification_lock")
    void migrationLeavesOnlyTheJobCoreLockTable() throws Exception {
        assertThat(tableExists("job_run_lock")).as("job-core's lock table was created").isTrue();
        assertThat(tableExists("notification_lock")).as("this service's own was dropped").isFalse();
    }

    private boolean tableExists(String table) throws java.sql.SQLException {
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.PreparedStatement query = connection.prepareStatement(
                        "SELECT to_regclass(?) IS NOT NULL")) {
            query.setString(1, table);
            try (java.sql.ResultSet row = query.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        }
    }

    /**
     * The second platform gap - since folded into {@code job-core}, which is why this exercises
     * {@link RunLock} rather than a lock of this service's own. A digest run on three replicas sends
     * three digests to one person, and there is no SKIP LOCKED trick that fixes it: the work is
     * defined over a set of rows rather than over each row independently.
     *
     * <p>The lock's own properties - the {@code run_id} fence, lease expiry, release semantics - are
     * covered where the lock lives, in {@code JdbcRunLockIT}. What is being checked here is the thing
     * only this service can check: that the lock it actually wires up admits exactly one replica
     * against this service's real schema and connection pool.
     */
    @Test
    @DisplayName("only one replica enters a job guarded by the distributed lock")
    void distributedLockAdmitsExactlyOne() throws Exception {
        AtomicInteger entered = new AtomicInteger();
        List<Boolean> acquired = inParallel(REPLICAS, replica ->
                runLockFor("replica-" + replica).runIfAvailable(TEST_LOCK, LEASE, handle -> {
                    entered.incrementAndGet();
                    sleepBriefly();
                }));

        assertThat(acquired).filteredOn(Boolean::booleanValue)
                .as("exactly one replica took the lock").hasSize(1);
        assertThat(entered).as("and exactly one body ran").hasValue(1);
    }

    @Test
    @DisplayName("the lock is released after the job, so the next run can take it")
    void distributedLockIsReleased() {
        assertThat(runLockFor("replica-1").runIfAvailable(TEST_LOCK, LEASE, handle -> { })).isTrue();
        assertThat(runLockFor("replica-2").runIfAvailable(TEST_LOCK, LEASE, handle -> { })).isTrue();
    }

    /**
     * A job that stalled long enough to lose its lock must not be able to renew it out from under
     * whichever replica has since taken over - a failed renewal is the signal to stop working, and
     * both schedulers honour it by stopping mid-list.
     */
    @Test
    @DisplayName("a replica that no longer holds the lock cannot renew it")
    void renewalRequiresOwnership() {
        RunLockHandle held = runLockFor("replica-1").tryAcquire(TEST_LOCK, LEASE).orElseThrow();

        assertThat(held.renew(LEASE)).as("the holder keeps its lease").isTrue();
        assertThat(runLockFor("replica-2").tryAcquire(TEST_LOCK, LEASE))
                .as("and nobody else can take it").isEmpty();

        held.close();
        assertThat(held.renew(LEASE)).as("a released lease is not renewable").isFalse();
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

    /** One lock per "replica", each with its own owner string, exactly as three pods would have. */
    private RunLock runLockFor(String owner) {
        return new JdbcRunLock(dataSource, owner);
    }

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
