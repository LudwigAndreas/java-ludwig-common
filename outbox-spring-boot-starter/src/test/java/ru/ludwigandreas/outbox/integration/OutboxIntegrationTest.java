package ru.ludwigandreas.outbox.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.integration.testmodel.StubOutboxDispatcher;
import ru.ludwigandreas.outbox.integration.testmodel.TransactionalPublishHelper;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.scheduler.OutboxOutcomeRecorder;
import ru.ludwigandreas.outbox.scheduler.OutboxProcessingService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full claim/dispatch/outcome pipeline against a real Postgres database (schema applied
 * via the shipped Liquibase changelog, cross-checked against the JPA mappings by {@code ddl-auto=validate}):
 * SKIP LOCKED concurrent claiming, per-key ordering, idempotency, retry/dead-lettering and stale reclaim.
 */
@SpringBootTest(classes = OutboxTestApplication.class, properties = "ludwig.outbox.retry.max-attempts=2")
@Testcontainers
class OutboxIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransactionalPublishHelper publishHelper;

    @Autowired
    private OutboxProcessingService processingService;

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private OutboxOutcomeRecorder outcomeRecorder;

    @Autowired
    private StubOutboxDispatcher dispatcher;

    @BeforeEach
    void resetState() {
        dispatcher.reset();
        repository.deleteAll();
    }

    private static OutboxEvent.OutboxEventBuilder event(String aggregateId, String eventType) {
        return OutboxEvent.builder().aggregateType("Order").aggregateId(aggregateId).eventType(eventType)
                .payload(Map.of("id", aggregateId));
    }

    @Test
    void publishThenPollThenDispatchReachesPublished() {
        publishHelper.publish(event("1", "OrderCreated").build());

        int claimed = processingService.processBatch();

        assertThat(claimed).isEqualTo(1);
        assertThat(dispatcher.dispatched()).hasSize(1);
        OutboxMessage stored = repository.findAll().get(0);
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isNotNull();
    }

    @Test
    void concurrentPollersClaimDisjointBatches() throws Exception {
        for (int i = 0; i < 20; i++) {
            publishHelper.publish(event(String.valueOf(i), "OrderCreated").build());
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<List<OutboxMessage>> claim = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return repository.pollBatch(10, Instant.now(), Thread.currentThread().getName());
        };

        try {
            Future<List<OutboxMessage>> future1 = executor.submit(claim);
            Future<List<OutboxMessage>> future2 = executor.submit(claim);
            List<OutboxMessage> claimedByFirst = future1.get(10, TimeUnit.SECONDS);
            List<OutboxMessage> claimedBySecond = future2.get(10, TimeUnit.SECONDS);

            Set<UUID> firstIds = claimedByFirst.stream().map(OutboxMessage::getId).collect(Collectors.toSet());
            Set<UUID> secondIds = claimedBySecond.stream().map(OutboxMessage::getId).collect(Collectors.toSet());

            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
            assertThat(firstIds.size() + secondIds.size()).isEqualTo(20);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void orderingKeyBlocksLaterMessagesUntilEarlierOneResolves() {
        publishHelper.publish(event("1", "Step1").orderingKey("K1").build());
        publishHelper.publish(event("1", "Step2").orderingKey("K1").build());
        publishHelper.publish(event("1", "Step3").orderingKey("K1").build());

        List<OutboxMessage> firstPoll = repository.pollBatch(10, Instant.now(), "worker");
        assertThat(firstPoll).extracting(OutboxMessage::getEventType).containsExactly("Step1");

        UUID step1Id = firstPoll.get(0).getId();
        outcomeRecorder.recordFailure(step1Id, "transient failure", true);

        List<OutboxMessage> secondPoll = repository.pollBatch(10, Instant.now(), "worker");
        assertThat(secondPoll).isEmpty();

        OutboxMessage step1 = repository.getByIdOrThrow(step1Id);
        step1.setStatus(OutboxStatus.PUBLISHED);
        repository.saveAndFlush(step1);

        List<OutboxMessage> thirdPoll = repository.pollBatch(10, Instant.now(), "worker");
        assertThat(thirdPoll).extracting(OutboxMessage::getEventType).containsExactly("Step2");
    }

    @Test
    void republishingSameIdempotencyKeyReturnsExistingRow() {
        Optional<OutboxMessage> first = publishHelper.publish(event("1", "OrderCreated").idempotencyKey("idem-1").build());
        Optional<OutboxMessage> second = publishHelper.publish(event("1", "OrderCreated").idempotencyKey("idem-1").build());

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get().getId()).isEqualTo(first.get().getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void exhaustingRetriesMovesMessageToDeadLetter() {
        dispatcher.setResultFunction(message -> DispatchResult.failure("boom", true));
        publishHelper.publish(event("1", "OrderCreated").build());

        processingService.processBatch();
        UUID id = repository.findAll().get(0).getId();
        OutboxMessage afterFirstAttempt = repository.getByIdOrThrow(id);
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(OutboxStatus.FAILED);
        afterFirstAttempt.setNextAttemptAt(Instant.now());
        repository.saveAndFlush(afterFirstAttempt);

        processingService.processBatch();

        OutboxMessage finalState = repository.getByIdOrThrow(id);
        assertThat(finalState.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(finalState.getAttempts()).isEqualTo(2);
    }

    @Test
    void staleProcessingRowsAreReclaimedToPending() {
        publishHelper.publish(event("1", "OrderCreated").build());
        List<OutboxMessage> claimed = repository.pollBatch(10, Instant.now(), "crashed-worker");
        assertThat(claimed).hasSize(1);

        UUID id = claimed.get(0).getId();
        OutboxMessage stuck = repository.getByIdOrThrow(id);
        stuck.setLockedAt(Instant.now().minus(Duration.ofMinutes(10)));
        repository.saveAndFlush(stuck);

        int reclaimed = repository.reclaimStale(Instant.now().minus(Duration.ofMinutes(5)));

        assertThat(reclaimed).isEqualTo(1);
        OutboxMessage reclaimedMessage = repository.getByIdOrThrow(id);
        assertThat(reclaimedMessage.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reclaimedMessage.getLockedAt()).isNull();
        assertThat(reclaimedMessage.getLockedBy()).isNull();
    }
}
