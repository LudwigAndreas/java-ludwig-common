package ru.ludwigandreas.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.idempotency.lifecycle.IdempotencyPurgeJob;
import ru.ludwigandreas.idempotency.store.PostgresIdempotencyStore;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;

/**
 * Case 7: the purge removes only expired claims, and runs on one replica under contention.
 *
 * <p>The second half is the one worth a container. Three replicas purging concurrently would not corrupt
 * anything - the statements are idempotent deletes - but they would take conflicting locks on the same pages
 * of a table receiving an insert per protected request, and two of the three would do nothing but contend.
 * The lease is what makes that not happen, and the assertion below is that a purge which cannot get the lease
 * does <em>nothing</em> rather than proceeding anyway.
 */
@SpringBootTest(classes = TestApplication.class,
        properties = {
            // The job is constructed by hand here so its runs are deterministic; a scheduled one would fire
            // in the middle of a case and make the counts depend on timing.
            "ludwig.idempotency.purge.enabled=false",
            "ludwig.idempotency.purge.batch-size=2"
        })
class RetentionPurgeIT extends PostgresBackedTest {

    private static final Duration SHORT = Duration.ofMinutes(5);

    private static final Duration LONG = Duration.ofDays(30);

    private static final Duration LEASE = Duration.ofMinutes(1);

    @Autowired
    private PostgresIdempotencyStore store;

    @Autowired
    private IdempotencyProperties properties;

    @Autowired
    private RunLock lock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private IdempotencyPurgeJob job;

    @BeforeEach
    void reset() {
        ((MutableClock) clock).reset();
        jdbc.update("DELETE FROM idempotency_claim");
        jdbc.update("DELETE FROM job_run_lock");
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        job = new IdempotencyPurgeJob(scheduler, store, lock, properties, IdempotencyMetrics.NOOP, clock);
    }

    @Test
    @DisplayName("the purge drops every expired claim and leaves the live ones, across several batches")
    void purgeWalksBatchesAndSpareTheLive() {
        for (int i = 0; i < 5; i++) {
            store.claim(ClaimRequest.standalone("http", "short-" + i, UUID.randomUUID(), SHORT, LEASE));
        }
        store.claim(ClaimRequest.standalone("http", "live", UUID.randomUUID(), LONG, LEASE));

        ((MutableClock) clock).advance(SHORT.plusMinutes(1));
        assertThat(job.runNow()).isTrue();

        // batch-size is 2 and five rows expired, so this also pins that a run walks more than one batch -
        // a purge that stopped after the first would silently never catch up on a busy service.
        assertThat(totalClaims()).isEqualTo(1);
        assertThat(claimExists("live")).isTrue();
    }

    @Test
    @DisplayName("a replica that cannot take the lease purges nothing")
    void oneReplicaAtATime() {
        store.claim(ClaimRequest.standalone("http", "expired", UUID.randomUUID(), SHORT, LEASE));
        ((MutableClock) clock).advance(SHORT.plusMinutes(1));

        Optional<RunLockHandle> heldElsewhere =
                lock.tryAcquire(properties.getPurge().getLockName(), Duration.ofMinutes(5));
        assertThat(heldElsewhere).isPresent();

        try {
            // runNow() returns true because the tick ran; what must not have happened is the delete.
            job.runNow();
            assertThat(totalClaims())
                    .describedAs("a purge without the lease must not touch the table")
                    .isEqualTo(1);
        } finally {
            heldElsewhere.get().close();
        }

        assertThat(job.runNow()).isTrue();
        assertThat(totalClaims())
                .describedAs("and must purge once the lease is free")
                .isZero();
    }

    private long totalClaims() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM idempotency_claim", Long.class);
        return count == null ? 0L : count;
    }

    private boolean claimExists(String key) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_claim WHERE idempotency_key = ?", Long.class, key);
        return count != null && count > 0;
    }
}
