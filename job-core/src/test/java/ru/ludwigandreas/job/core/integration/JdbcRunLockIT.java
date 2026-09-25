package ru.ludwigandreas.job.core.integration;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.lock.RunLockListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lease, against a real PostgreSQL.
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>Every correctness property of {@link JdbcRunLock} is a statement about how PostgreSQL behaves
 * when two transactions meet on one row: {@code ON CONFLICT DO NOTHING} making the row-ensure a
 * no-op, {@code FOR UPDATE SKIP LOCKED} turning contention into an immediate "no" rather than a
 * stall, {@code RETURNING} telling the winner it won, and {@code now()} being the database's clock
 * rather than any pod's. A mocked {@code DataSource} would assert that the code sends the strings it
 * sends, which is not the thing that has to be true. An in-memory database would answer differently
 * and pass anyway.
 *
 * <p>This module used to have no test of its own here at all, on the grounds that its consumers
 * exercised the lock through their own integration tests. That was defensible while the lock was one
 * of two; now that it is the platform's only distributed lock, the properties below have to be
 * pinned where the lock lives rather than inferred from somewhere downstream.
 *
 * <p>Threads stand in for replicas, which is faithful because nothing in the mechanism is
 * per-process: the coordination is entirely in the database, so two threads on two connections are
 * indistinguishable from two pods on two connections.
 */
@Testcontainers
class JdbcRunLockIT {

    /**
     * Pinned by name, version <em>and</em> digest.
     *
     * <p>A tag alone can be re-pointed at different content, so a test would silently change what it
     * executes. Company policy is name plus version plus digest everywhere, and a test container is
     * not an exception to it.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:"
                    + "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    private static final String LOCK_NAME = "test-job";

    /** Long enough that nothing here expires by accident while a case is running. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final int REPLICAS = 4;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    private static DataSource dataSource;

    /**
     * The schema comes from this module's own shipped changelog rather than from DDL written here.
     *
     * <p>That makes the migration part of what is under test: a column renamed in the changelog and
     * not in {@link JdbcRunLock} fails here, which is the only place it would fail before production.
     *
     * <p>Applied through {@link SpringLiquibase} - the same class, pointed at the same changelog
     * path, that {@code JobCoreLiquibaseAutoConfiguration} uses in a real application. Driving the
     * raw {@code Liquibase} API instead would be a second way of applying the schema, and a second
     * way is a way that can start disagreeing with the one production uses.
     *
     * @throws Exception if the container's schema cannot be created
     */
    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        dataSource = source;

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/job-core/job-core-changelog.xml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    @AfterAll
    static void closeContainerResources() {
        dataSource = null;
    }

    /**
     * The lock row survives a release by design - the owner is nulled and the expiry is moved to now,
     * so the table stays bounded by the number of lock names. A leftover row is therefore not a bug,
     * but a leftover <em>live lease</em> from an earlier case would decide the next one, so the table
     * is emptied rather than asserted to be empty.
     *
     * @throws Exception if the table cannot be cleared
     */
    @BeforeEach
    void resetState() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM job_run_lock");
        }
    }

    @Test
    @DisplayName("a second instance cannot take a lease that is live")
    void oneLeaseAtATime() {
        RunLock first = lockFor("replica-1");
        RunLock second = lockFor("replica-2");

        try (RunLockHandle held = first.tryAcquire(LOCK_NAME, LEASE).orElseThrow()) {
            assertThat(held.owner()).isEqualTo("replica-1");
            assertThat(second.tryAcquire(LOCK_NAME, LEASE))
                    .as("the live lease is held elsewhere").isEmpty();
        }
    }

    /**
     * The whole reason the lock is a lease. A pod killed mid-run renews nothing, and the job has to
     * become runnable again without anybody noticing or intervening.
     */
    @Test
    @DisplayName("a lapsed lease is claimable by another instance, with no release")
    void lapsedLeaseIsClaimable() {
        RunLock dying = lockFor("doomed-replica");
        RunLock survivor = lockFor("replica-2");

        // Acquired with a lease that has already run out by the time the next statement executes,
        // which is what a dead holder looks like from the database's point of view: a row whose
        // expires_at is in the past and whose owner will never touch it again. The handle is
        // deliberately not closed - a crashed pod closes nothing.
        assertThat(dying.tryAcquire(LOCK_NAME, Duration.ZERO)).isPresent();

        assertThat(survivor.tryAcquire(LOCK_NAME, LEASE))
                .as("the lapsed lease is taken over without any participation from its holder")
                .isPresent();
    }

    /**
     * The fence. A renewal matches {@code run_id} as well as {@code owner}, so a lease that lapsed and
     * was re-acquired - even by this very same instance - counts as lost rather than as still held.
     *
     * <p>This is the property neither of the platform's earlier lock implementations had: matching on
     * the owner alone, a pod that stalled past its lease and then re-acquired the lock would renew as
     * though nothing had happened, while the work it was part-way through belonged to a run that had
     * already been superseded.
     */
    @Test
    @DisplayName("a renewal after the lease lapsed and was re-acquired by the same owner fails")
    void renewalIsFencedByRunId() {
        RunLock lock = lockFor("replica-1");

        RunLockHandle stalled = lock.tryAcquire(LOCK_NAME, Duration.ZERO).orElseThrow();
        RunLockHandle current = lock.tryAcquire(LOCK_NAME, LEASE).orElseThrow();

        assertThat(current.runId()).as("a second acquisition is a different run")
                .isNotEqualTo(stalled.runId());
        assertThat(stalled.renew(LEASE))
                .as("the superseded run is told to stop, although the owner string still matches")
                .isFalse();
        assertThat(current.renew(LEASE)).as("the authoritative run keeps its lease").isTrue();

        current.close();
    }

    @Test
    @DisplayName("a released lease is immediately claimable, without waiting out the TTL")
    void releaseIsImmediate() {
        RunLock first = lockFor("replica-1");
        RunLock second = lockFor("replica-2");

        first.tryAcquire(LOCK_NAME, LEASE).orElseThrow().close();

        try (RunLockHandle taken = second.tryAcquire(LOCK_NAME, LEASE).orElseThrow()) {
            assertThat(taken.owner()).isEqualTo("replica-2");
        }
    }

    /**
     * A thrown run must cost nothing. Letting the lease expire instead would also be correct, but it
     * would block the next scheduled run for a full lease period after a failure that took
     * milliseconds.
     */
    @Test
    @DisplayName("runIfAvailable releases when the work throws, and propagates the exception")
    void failedWorkStillReleases() {
        RunLock lock = lockFor("replica-1");
        RunLock other = lockFor("replica-2");

        assertThatThrownBy(() -> lock.runIfAvailable(LOCK_NAME, LEASE, handle -> {
            throw new IllegalStateException("run failed");
        }))
                .as("the lock decides nothing about whether a failed run stops the schedule")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run failed");

        assertThat(other.runIfAvailable(LOCK_NAME, LEASE, handle -> { }))
                .as("the next run is not blocked by the previous one's failure").isTrue();
    }

    @Test
    @DisplayName("runIfAvailable with no TTL uses the configured default lease")
    void defaultLeaseIsUsed() {
        RunLock lock = new JdbcRunLock(dataSource, "replica-1", Duration.ofMinutes(7),
                RunLockListener.NOOP);
        RunLock other = lockFor("replica-2");

        assertThat(lock.defaultLeaseTtl()).isEqualTo(Duration.ofMinutes(7));
        assertThat(lock.runIfAvailable(LOCK_NAME, handle -> assertThat(
                other.tryAcquire(LOCK_NAME, LEASE)).isEmpty())).isTrue();
    }

    /**
     * The claim under real contention. If the row-ensure and the claim were not one
     * {@code SKIP LOCKED} statement - a select followed by an update, say - two replicas would both
     * observe a free lease and both enter the job, which is the single failure the lock exists to
     * prevent.
     *
     * @throws Exception if a worker fails
     */
    @Test
    @DisplayName("N instances contending on one name produce exactly one winner")
    void contentionHasOneWinner() throws Exception {
        AtomicInteger entered = new AtomicInteger();

        List<Boolean> ran = inParallel(REPLICAS, replica ->
                lockFor("replica-" + replica).runIfAvailable(LOCK_NAME, LEASE, handle -> {
                    entered.incrementAndGet();
                    // Long enough that a second replica would be inside the body at the same time if
                    // the claim were not doing its job.
                    sleepBriefly();
                }));

        assertThat(ran).filteredOn(Boolean::booleanValue)
                .as("exactly one instance took the lease").hasSize(1);
        assertThat(entered).as("and exactly one body ran").hasValue(1);
    }

    /**
     * What the acquisition and lost-lease counters are built on. The listener sees the same outcomes
     * the caller does, so a dashboard cannot disagree with the return value.
     */
    @Test
    @DisplayName("the listener observes acquisition, contention, loss and release")
    void listenerObservesEveryOutcome() {
        RecordingListener events = new RecordingListener();
        RunLock lock = new JdbcRunLock(dataSource, "replica-1", LEASE, events);
        RunLock other = new JdbcRunLock(dataSource, "replica-2", LEASE, events);

        RunLockHandle stalled = lock.tryAcquire(LOCK_NAME, Duration.ZERO).orElseThrow();
        Optional<RunLockHandle> taken = lock.tryAcquire(LOCK_NAME, LEASE);
        assertThat(other.tryAcquire(LOCK_NAME, LEASE)).isEmpty();
        assertThat(stalled.renew(LEASE)).isFalse();
        taken.orElseThrow().close();

        assertThat(events.acquired).isEqualTo(2);
        assertThat(events.contended).isEqualTo(1);
        assertThat(events.lost).isEqualTo(1);
        assertThat(events.released).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private static RunLock lockFor(String owner) {
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

    private static void sleepBriefly() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Counts what the lock reported, so the assertions can be about outcomes rather than meters. */
    private static final class RecordingListener implements RunLockListener {

        private int acquired;
        private int contended;
        private int lost;
        private int released;

        @Override
        public void onAcquired(String lockName, String owner, Duration leaseTtl) {
            acquired++;
        }

        @Override
        public void onContended(String lockName, String owner) {
            contended++;
        }

        @Override
        public void onLost(String lockName, String owner) {
            lost++;
        }

        @Override
        public void onReleased(String lockName, String owner) {
            released++;
        }
    }

    /** A unit of parallel work that may throw, which a plain {@code Function} cannot express. */
    @FunctionalInterface
    private interface ParallelTask<T> {
        T run(int worker) throws Exception;
    }
}
