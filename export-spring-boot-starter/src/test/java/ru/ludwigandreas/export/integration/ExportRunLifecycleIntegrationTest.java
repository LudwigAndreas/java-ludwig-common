package ru.ludwigandreas.export.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.export.api.RunStatus;
import ru.ludwigandreas.export.entity.ExportReportOutput;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.exception.ExportQuotaExceededException;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;

/**
 * The run lifecycle against a real Postgres: claiming, leasing, reclaiming, retrying and purging.
 *
 * <p>Against a real database on purpose, and not only because of {@code FOR UPDATE SKIP LOCKED}.
 * Every property asserted here - that two instances cannot claim one run, that an expired lease
 * becomes claimable, that a duplicate idempotency key is refused - is a property of concurrent
 * transactions, and an in-memory database that serialises them would pass all of these tests while
 * the production database failed them.
 *
 * <p>The schema comes from the shipped Liquibase changelog and is cross-checked against the JPA
 * mappings by {@code ddl-auto=validate}, so a column added to an entity and forgotten in the
 * changelog fails here rather than on somebody's deployment.
 */
@SpringBootTest(classes = ExportTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                // The application has no master changelog of its own here. This module's schema
                // comes from its own SpringLiquibase bean, which is the whole point of registering a
                // second one - see ExportLiquibaseAutoConfiguration.
                "spring.liquibase.enabled=false",
                "ludwig.export.poller.enabled=false",
                // The test application contributes its own sink, so it names it here - which is
                // exactly what a service with an object-storage sink does, and what the validator's
                // sink.type check exists to make somebody do.
                "ludwig.export.sink.type=testReportSink",
                "ludwig.export.quota.concurrent-runs-per-user=2",
                "ludwig.export.poller.max-attempts=2"
        })
@Testcontainers
class ExportRunLifecycleIntegrationTest {

    /**
     * Pinned by name, version <em>and</em> digest: a tag alone can be re-pointed at different
     * content, so a test would silently change what it executes.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    // The connection name is given explicitly because Spring Boot otherwise deduces it by parsing
    // the image name, and a name carrying both a tag and a digest is not parseable as a repository.
    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private ExportReportRunRepository runs;

    @Autowired
    private ExportReportOutputRepository outputs;

    @Autowired
    private ExportRunService lifecycle;

    @BeforeEach
    void reset() {
        outputs.deleteAll();
        runs.deleteAll();
    }

    private ExportReportRun newRun(String requester, String idempotencyKey) {
        ExportReportRun run = new ExportReportRun();
        run.setDefinitionKey("catalog.orders");
        run.setDefinitionVersion(1);
        run.setRequester(requester);
        run.setFormats(List.of("csv"));
        run.setLocale("en");
        run.setTimeZone("UTC");
        run.setIdempotencyKey(idempotencyKey);
        run.setMaxAttempts(2);
        run.setNextAttemptAt(Instant.now());
        run.setParameters(Map.of("from", "2026-01-01"));
        run.setColumnIds(List.of("number", "total"));
        return run;
    }

    @Test
    @DisplayName("the changelog and the entity mappings agree")
    void schemaValidates() {
        // Reaching here at all means Hibernate's ddl-auto=validate accepted the Liquibase schema.
        assertThat(runs.count()).isZero();
    }

    @Test
    @DisplayName("two instances claiming at once never take the same run")
    void claimsAreExclusive() throws Exception {
        for (int i = 0; i < 20; i++) {
            runs.save(newRun("alice", "key-" + i));
        }
        int instances = 4;
        CyclicBarrier startTogether = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<Callable<List<UUID>>> claims = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            String owner = "instance-" + i;
            claims.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return runs.claimDue(owner, Instant.now(), Duration.ofMinutes(5), 10).stream()
                        .map(ExportReportRun::getId)
                        .toList();
            });
        }

        List<UUID> allClaimed = new ArrayList<>();
        try {
            for (Future<List<UUID>> claimed : pool.invokeAll(claims)) {
                allClaimed.addAll(claimed.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allClaimed).hasSize(20);
        assertThat(Set.copyOf(allClaimed)).hasSize(20);
        assertThat(runs.findAll()).allSatisfy(run -> {
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(run.getClaimedBy()).isNotNull();
            assertThat(run.getAttempts()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a claim takes the lease and counts the attempt in the same statement")
    void claimTakesTheLease() {
        ExportReportRun saved = runs.save(newRun("alice", "key-lease"));
        Instant now = Instant.now();

        List<ExportReportRun> claimed = runs.claimDue("instance-a", now, Duration.ofMinutes(5), 1);

        assertThat(claimed).hasSize(1);
        ExportReportRun run = runs.findById(saved.getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getClaimedBy()).isEqualTo("instance-a");
        assertThat(run.getAttempts()).isEqualTo(1);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getLeaseUntil()).isAfter(now);
    }

    @Test
    @DisplayName("an expired lease is reclaimed, and a live one is left alone")
    void reclaimsOnlyExpiredLeases() {
        // The claim orders by next_attempt_at, so the two runs are given different ones rather
        // than the test assuming which of two identical rows is taken first.
        ExportReportRun dead = newRun("alice", "key-dead");
        dead.setNextAttemptAt(Instant.now().minus(Duration.ofHours(1)));
        dead = runs.save(dead);
        ExportReportRun alive = runs.save(newRun("alice", "key-alive"));

        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        runs.claimDue("instance-a", past, Duration.ofMinutes(1), 1);
        runs.claimDue("instance-b", Instant.now(), Duration.ofMinutes(10), 1);
        assertThat(runs.findById(dead.getId()).orElseThrow().getClaimedBy()).isEqualTo("instance-a");
        assertThat(runs.findById(alive.getId()).orElseThrow().getClaimedBy()).isEqualTo("instance-b");

        int reclaimed = runs.reclaimExpired(Instant.now(), 10);

        assertThat(reclaimed).isEqualTo(1);
        assertThat(runs.findById(dead.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(runs.findById(dead.getId()).orElseThrow().getClaimedBy()).isNull();
        // The attempt stays counted, which is what stops a run that kills its instance looping forever.
        assertThat(runs.findById(dead.getId()).orElseThrow().getAttempts()).isEqualTo(1);
        assertThat(runs.findById(alive.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("a lease renews only for the instance that holds it")
    void renewsOnlyForTheHolder() {
        ExportReportRun saved = runs.save(newRun("alice", "key-renew"));
        runs.claimDue("instance-a", Instant.now(), Duration.ofMinutes(5), 1);

        assertThat(runs.renewLease(saved.getId(), "instance-a", Instant.now(), Duration.ofMinutes(5)))
                .isTrue();
        assertThat(runs.renewLease(saved.getId(), "instance-b", Instant.now(), Duration.ofMinutes(5)))
                .isFalse();
    }

    @Test
    @DisplayName("the same idempotency key returns the first run rather than queuing a second")
    void idempotencyKeyIsHonoured() {
        ExportReportRun first = lifecycle.submit(newRun("alice", "same-key"));
        ExportReportRun second = lifecycle.submit(newRun("alice", "same-key"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the concurrent quota refuses a run past the ceiling")
    void enforcesTheConcurrentQuota() {
        lifecycle.submit(newRun("alice", "quota-1"));
        lifecycle.submit(newRun("alice", "quota-2"));

        assertThatThrownBy(() -> lifecycle.submit(newRun("alice", "quota-3")))
                .isInstanceOf(ExportQuotaExceededException.class)
                .hasMessageContaining("concurrent");
        // Another requester is unaffected: the quota is per user, not per service.
        assertThat(lifecycle.submit(newRun("bob", "quota-4")).getId()).isNotNull();
    }

    @Test
    @DisplayName("a failure retries with a backoff until the attempt budget runs out")
    void retriesThenFails() {
        ExportReportRun saved = lifecycle.submit(newRun("alice", "key-retry"));

        runs.claimDue("instance-a", Instant.now(), Duration.ofMinutes(5), 1);
        lifecycle.recordFailure(saved.getId(), new IllegalStateException("first"));
        ExportReportRun afterFirst = runs.findById(saved.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(afterFirst.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(afterFirst.getClaimedBy()).isNull();

        runs.claimDue("instance-a", afterFirst.getNextAttemptAt().plusSeconds(1),
                Duration.ofMinutes(5), 1);
        lifecycle.recordFailure(saved.getId(), new IllegalStateException("second"));
        ExportReportRun afterSecond = runs.findById(saved.getId()).orElseThrow();

        assertThat(afterSecond.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(afterSecond.getFinishedAt()).isNotNull();
        assertThat(afterSecond.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a run that is not due yet is not claimed")
    void respectsTheBackoffWindow() {
        ExportReportRun run = newRun("alice", "key-later");
        run.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(5)));
        runs.save(run);

        assertThat(runs.claimDue("instance-a", Instant.now(), Duration.ofMinutes(5), 10)).isEmpty();
    }

    @Test
    @DisplayName("cancelling a pending run is immediate; cancelling a running one is a request")
    void cancellationDependsOnState() {
        ExportReportRun pending = lifecycle.submit(newRun("alice", "key-cancel-pending"));
        ExportReportRun running = lifecycle.submit(newRun("bob", "key-cancel-running"));
        runs.claimDue("instance-a", Instant.now(), Duration.ofMinutes(5), 10);

        // The claim took both, so put the first back to make the two states differ.
        ExportReportRun first = runs.findById(pending.getId()).orElseThrow();
        first.setStatus(RunStatus.PENDING);
        runs.save(first);

        assertThat(lifecycle.requestCancel(pending.getId())).isTrue();
        assertThat(runs.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.CANCELLED);

        assertThat(lifecycle.requestCancel(running.getId())).isTrue();
        ExportReportRun stillRunning = runs.findById(running.getId()).orElseThrow();
        assertThat(stillRunning.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(stillRunning.isCancelRequested()).isTrue();
        assertThat(lifecycle.isCancellationRequested(running.getId())).isTrue();
    }

    @Test
    @DisplayName("a terminal run cannot be cancelled")
    void refusesToCancelATerminalRun() {
        ExportReportRun run = lifecycle.submit(newRun("alice", "key-done"));
        runs.claimDue("instance-a", Instant.now(), Duration.ofMinutes(5), 1);
        lifecycle.recordCancelled(run.getId(), 0);

        assertThat(lifecycle.requestCancel(run.getId())).isFalse();
    }

    @Test
    @DisplayName("an expired output is found by the purge query and a live one is not")
    void findsExpiredOutputs() {
        ExportReportRun run = runs.save(newRun("alice", "key-outputs"));
        outputs.save(output(run.getId(), "csv", Instant.now().minus(Duration.ofDays(1))));
        outputs.save(output(run.getId(), "xlsx", Instant.now().plus(Duration.ofDays(1))));

        List<ExportReportOutput> expired = outputs.findByPurgedAtIsNullAndExpiresAtBefore(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).getFormatId()).isEqualTo("csv");
    }

    @Test
    @DisplayName("a purged output keeps its row, so the trail outlives the bytes")
    void purgingKeepsTheRow() {
        ExportReportRun run = runs.save(newRun("alice", "key-purge"));
        ExportReportOutput saved = outputs.save(
                output(run.getId(), "csv", Instant.now().minus(Duration.ofDays(1))));

        saved.setPurgedAt(Instant.now());
        outputs.save(saved);

        ExportReportOutput after = outputs.findById(saved.getId()).orElseThrow();
        assertThat(after.isDownloadable()).isFalse();
        assertThat(after.getSha256()).isNotNull();
        assertThat(outputs.findByPurgedAtIsNullAndExpiresAtBefore(Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    @DisplayName("deleting a run takes its outputs with it")
    void cascadesOutputDeletion() {
        ExportReportRun run = runs.save(newRun("alice", "key-cascade"));
        outputs.save(output(run.getId(), "csv", Instant.now().plus(Duration.ofDays(1))));

        runs.deleteById(run.getId());

        assertThat(outputs.findByRunId(run.getId())).isEmpty();
    }

    private ExportReportOutput output(UUID runId, String formatId, Instant expiresAt) {
        ExportReportOutput output = new ExportReportOutput();
        output.setRunId(runId);
        output.setFormatId(formatId);
        output.setSinkUri("test/" + runId + "/" + formatId);
        output.setFileName("report." + formatId);
        output.setMediaType("text/csv");
        output.setSizeBytes(1_024);
        output.setSha256("0".repeat(64));
        output.setExpiresAt(expiresAt);
        return output;
    }
}
