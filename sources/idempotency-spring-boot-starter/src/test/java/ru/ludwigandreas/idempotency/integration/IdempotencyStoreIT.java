package ru.ludwigandreas.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimOutcome;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.StoredResponse;
import ru.ludwigandreas.idempotency.store.PostgresIdempotencyStore;

/**
 * The claim, against a real PostgreSQL and under real contention.
 *
 * <p>Threads stand in for replicas, which is faithful because nothing in the mechanism is per-process: the
 * coordination is entirely in the database, so N threads on N connections are indistinguishable from N pods
 * on N connections.
 */
@SpringBootTest(classes = TestApplication.class,
        properties = {"ludwig.idempotency.purge.enabled=false"})
class IdempotencyStoreIT extends PostgresBackedTest {

    /** Enough concurrency that a read-then-write implementation would certainly fail. */
    private static final int REPLICAS = 8;

    private static final Duration TTL = Duration.ofHours(1);

    private static final Duration LEASE = Duration.ofMinutes(1);

    @Autowired
    private PostgresIdempotencyStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).reset();
        jdbc.update("DELETE FROM idempotency_claim");
        transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Case 1: N threads, one key, one winner, N-1 duplicates, and the winner's request id returned to all.
     *
     * <p>The last clause is the one that matters and the one a weaker test omits. A dedup that only tells the
     * losers "you lost" leaves them with nothing to answer their own caller with; what makes a redelivery
     * indistinguishable from the original call is that every loser is handed the <em>winner's</em> id.
     */
    @Test
    @DisplayName("N replicas, one key: one winner and the winner's id returned to every loser")
    void oneWinnerUnderContention() throws Exception {
        String key = "contended-" + UUID.randomUUID();
        CountDownLatch startLine = new CountDownLatch(1);

        List<ClaimResult> results = inParallel(REPLICAS, () -> {
            startLine.await();
            return transactions.execute(status -> store.claim(
                    ClaimRequest.transactional("kafka", key, UUID.randomUUID(), TTL)));
        }, startLine);

        List<ClaimResult> winners = results.stream().filter(ClaimResult::won).toList();
        assertThat(winners).hasSize(1);
        UUID winner = winners.get(0).owner();
        assertThat(results).allSatisfy(result -> assertThat(result.owner()).isEqualTo(winner));
        assertThat(results.stream().filter(result -> !result.won())).hasSize(REPLICAS - 1);
        assertThat(claimCount(key)).isEqualTo(1);
    }

    /**
     * Case 2: the exact failure the transactional mode exists to prevent.
     *
     * <p>A claim that committed independently would leave the key reserved for a request whose transaction
     * then rolled back, and the retry of that request would be rejected as a duplicate of something that
     * does not exist. This asserts the opposite: the rollback takes the claim with it, and the retry wins.
     */
    @Test
    @DisplayName("TRANSACTIONAL: the caller's rollback frees the key and the retry succeeds")
    void rollbackFreesTheKey() {
        String key = "rolled-back-" + UUID.randomUUID();
        UUID first = UUID.randomUUID();

        transactions.execute(status -> {
            ClaimResult claim = store.claim(ClaimRequest.transactional("kafka", key, first, TTL));
            assertThat(claim.won()).isTrue();
            status.setRollbackOnly();
            return null;
        });

        assertThat(claimCount(key)).isZero();

        UUID retry = UUID.randomUUID();
        ClaimResult second = transactions.execute(status ->
                store.claim(ClaimRequest.transactional("kafka", key, retry, TTL)));
        assertThat(second.won()).isTrue();
        assertThat(second.owner()).isEqualTo(retry);
    }

    /**
     * The other half of {@code MANDATORY}: refusing rather than degrading.
     *
     * <p>Degrading to an independent commit is the failure above, and it would only show up as a key that
     * cannot be retried after some unrelated rollback, days later.
     */
    @Test
    @DisplayName("TRANSACTIONAL: a claim with no transaction open is refused, not committed independently")
    void transactionalRefusesWithoutATransaction() {
        assertThatThrownBy(() -> store.claim(
                ClaimRequest.transactional("kafka", "no-tx", UUID.randomUUID(), TTL)))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("STANDALONE");
    }

    /** Case 3a: an in-progress claim tells a duplicate to come back, and does not hand over a response. */
    @Test
    @DisplayName("STANDALONE: an in-progress duplicate is told IN_PROGRESS with nothing to replay")
    void inProgressDuplicate() {
        String key = "in-flight-" + UUID.randomUUID();
        ClaimResult first = store.claim(
                ClaimRequest.standalone("http", key, UUID.randomUUID(), TTL, LEASE));
        assertThat(first.won()).isTrue();

        ClaimResult second = store.claim(
                ClaimRequest.standalone("http", key, UUID.randomUUID(), TTL, LEASE));

        assertThat(second.outcome()).isEqualTo(ClaimOutcome.IN_PROGRESS);
        assertThat(second.owner()).isEqualTo(first.owner());
        assertThat(second.replayable()).isEmpty();
        // The Retry-After a caller is given comes from the holder's lease, not the TTL: the honest earliest
        // moment to come back is when the holder would have lost the claim had it died.
        assertThat(second.expiresAt()).isBeforeOrEqualTo(clock.instant().plus(LEASE));
    }

    /** Case 3b: a completed claim replays the stored response byte for byte. */
    @Test
    @DisplayName("STANDALONE: a completed duplicate replays the stored response byte for byte")
    void completedDuplicateReplays() {
        String key = "completed-" + UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.claim(ClaimRequest.standalone("http", key, owner, TTL, LEASE));

        byte[] body = "{\"id\":\"7\",\"state\":\"ACCEPTED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StoredResponse original = StoredResponse.of(201, "application/json", body)
                .withHeader("Location", "/api/v1/orders/7");
        assertThat(store.complete("http", key, owner, original)).isTrue();

        ClaimResult duplicate = store.claim(
                ClaimRequest.standalone("http", key, UUID.randomUUID(), TTL, LEASE));

        assertThat(duplicate.outcome()).isEqualTo(ClaimOutcome.COMPLETED);
        StoredResponse replayed = duplicate.replayable().orElseThrow();
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.contentType()).isEqualTo("application/json");
        assertThat(replayed.body()).isEqualTo(body);
        assertThat(replayed.headers()).containsEntry("Location", "/api/v1/orders/7");
    }

    /** Case 3c: a failed claim lets the retry through, because that is what the retry is for. */
    @Test
    @DisplayName("STANDALONE: a FAILED claim lets the retry through")
    void failedClaimIsReclaimable() {
        String key = "failed-" + UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.claim(ClaimRequest.standalone("http", key, owner, TTL, LEASE));
        assertThat(store.fail("http", key, owner, "the handler threw")).isTrue();

        UUID retry = UUID.randomUUID();
        ClaimResult second = store.claim(ClaimRequest.standalone("http", key, retry, TTL, LEASE));

        assertThat(second.won()).isTrue();
        assertThat(second.owner()).isEqualTo(retry);
        // Reclaimed, not duplicated: the unique constraint is still one row per (scope, key).
        assertThat(claimCount(key)).isEqualTo(1);
    }

    /** Case 4: a dead holder's lease expires and the key becomes claimable. */
    @Test
    @DisplayName("STANDALONE: a dead holder's lease expires and the key becomes claimable")
    void deadHolderLoses() {
        String key = "abandoned-" + UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        store.claim(ClaimRequest.standalone("http", key, dead, TTL, LEASE));

        // Still inside the lease: nobody else may have it. A key stuck in progress would otherwise be a
        // caller who can never retry, which is worse than a double execution because it is permanent.
        assertThat(store.claim(ClaimRequest.standalone("http", key, UUID.randomUUID(), TTL, LEASE))
                .outcome()).isEqualTo(ClaimOutcome.IN_PROGRESS);

        ((MutableClock) clock).advance(LEASE.plusSeconds(1));

        UUID successor = UUID.randomUUID();
        ClaimResult taken = store.claim(ClaimRequest.standalone("http", key, successor, TTL, LEASE));
        assertThat(taken.won()).isTrue();
        assertThat(taken.owner()).isEqualTo(successor);

        // And the dead holder can no longer publish a response for work the successor is redoing.
        assertThat(store.complete("http", key, dead, StoredResponse.of(200, "text/plain", new byte[0])))
                .isFalse();
    }

    /**
     * A lease that lapsed cannot be renewed as though nothing had happened.
     *
     * <p>The {@code run_id} fencing {@code JdbcRunLock} gained, expressed against the lease: a holder that
     * paused long enough to lose its claim must be told so, because another instance may already be
     * repeating the work.
     */
    @Test
    @DisplayName("STANDALONE: a lapsed lease cannot be renewed")
    void lapsedLeaseCannotRenew() {
        String key = "lapsed-" + UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        store.claim(ClaimRequest.standalone("http", key, owner, TTL, LEASE));

        assertThat(store.renewLease("http", key, owner, LEASE)).isTrue();

        ((MutableClock) clock).advance(LEASE.plusSeconds(1));
        assertThat(store.renewLease("http", key, owner, LEASE)).isFalse();
    }

    /**
     * Case 6: the TTL boundary.
     *
     * <p>The TTL is a correctness parameter rather than housekeeping, and this is what that means: inside
     * the window a retry is recognised, past it the same call is new work. Note that the purge has not run -
     * expiry is decided in the claim statement, so the window means what the configuration says rather than
     * "the window, plus however long until the purge next ran".
     */
    @Test
    @DisplayName("a key one second inside its window dedups; one second past it does not")
    void ttlBoundary() {
        String key = "expiring-" + UUID.randomUUID();
        Duration shortTtl = Duration.ofMinutes(10);
        UUID first = UUID.randomUUID();
        store.claim(ClaimRequest.standalone("http", key, first, shortTtl, LEASE));
        store.complete("http", key, first, null);

        ((MutableClock) clock).advance(shortTtl.minusSeconds(1));
        assertThat(store.claim(ClaimRequest.standalone("http", key, UUID.randomUUID(), shortTtl, LEASE))
                .outcome()).isEqualTo(ClaimOutcome.COMPLETED);

        ((MutableClock) clock).advance(Duration.ofSeconds(2));
        assertThat(store.claim(ClaimRequest.standalone("http", key, UUID.randomUUID(), shortTtl, LEASE))
                .won()).isTrue();
    }

    /** Scopes are namespaces: the same key in two scopes is two claims. */
    @Test
    @DisplayName("the same key in two scopes is two claims")
    void scopesAreNamespaces() {
        String key = "shared-" + UUID.randomUUID();

        assertThat(store.claim(ClaimRequest.standalone("http", key, UUID.randomUUID(), TTL, LEASE)).won())
                .isTrue();
        // A Kafka record key and an HTTP Idempotency-Key come from different namespaces, and a collision
        // between them would silently drop a genuine request.
        assertThat(transactions.execute(status ->
                        store.claim(ClaimRequest.transactional("kafka", key, UUID.randomUUID(), TTL)))
                        .won())
                .describedAs("a claim in another scope must not see this one")
                .isTrue();
    }

    /** Case 7a: the purge removes only expired rows. */
    @Test
    @DisplayName("the purge removes only expired claims")
    void purgeIsSelective() {
        UUID owner = UUID.randomUUID();
        store.claim(ClaimRequest.standalone("http", "short", owner, Duration.ofMinutes(5), LEASE));
        store.claim(ClaimRequest.standalone("http", "long", UUID.randomUUID(), Duration.ofDays(7), LEASE));

        ((MutableClock) clock).advance(Duration.ofMinutes(6));
        long purged = store.purgeBatch(clock.instant(), 100);

        assertThat(purged).isEqualTo(1);
        assertThat(claimCount("long")).isEqualTo(1);
        assertThat(claimCount("short")).isZero();
    }

    /** A mode a store cannot serve is refused rather than substituted. */
    @Test
    @DisplayName("the Postgres store serves both modes")
    void postgresServesBothModes() {
        assertThat(store.supports(ClaimMode.TRANSACTIONAL)).isTrue();
        assertThat(store.supports(ClaimMode.STANDALONE)).isTrue();
    }

    /** N concurrent standalone claims also produce one winner, and the rest are told IN_PROGRESS. */
    @Test
    @DisplayName("N replicas racing a STANDALONE claim: one winner, the rest in progress")
    void standaloneContention() throws Exception {
        String key = "standalone-race-" + UUID.randomUUID();
        CountDownLatch startLine = new CountDownLatch(1);

        List<ClaimResult> results = inParallel(REPLICAS, () -> {
            startLine.await();
            return store.claim(ClaimRequest.standalone("http", key, UUID.randomUUID(), TTL, LEASE));
        }, startLine);

        assertThat(results.stream().filter(ClaimResult::won)).hasSize(1);
        Set<ClaimOutcome> outcomes = results.stream().map(ClaimResult::outcome)
                .collect(Collectors.toSet());
        assertThat(outcomes).containsExactlyInAnyOrder(ClaimOutcome.CLAIMED, ClaimOutcome.IN_PROGRESS);
        assertThat(claimCount(key)).isEqualTo(1);
    }

    private long claimCount(String key) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_claim WHERE idempotency_key = ?", Long.class, key);
        return count == null ? 0L : count;
    }

    /**
     * Runs {@code work} on {@code threads} threads at once, from one starting gun.
     *
     * <p>The latch matters: without it the threads start as the pool creates them and the first is often
     * finished before the last begins, which is a sequential test wearing a concurrent test's clothes.
     */
    private static List<ClaimResult> inParallel(int threads, Callable<ClaimResult> work,
                                                CountDownLatch startLine) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ClaimResult>> futures = IntStream.range(0, threads)
                    .mapToObj(ignored -> pool.submit(work))
                    .toList();
            startLine.countDown();
            List<ClaimResult> results = new java.util.ArrayList<>(threads);
            for (Future<ClaimResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
