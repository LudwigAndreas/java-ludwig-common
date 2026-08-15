package ru.ludwigandreas.outbox.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcherRegistry;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the claim -> dispatch -> record-outcome lifecycle for one poll cycle.
 * <ol>
 *   <li>{@link #processBatch()} claims a batch via {@link OutboxMessageRepository#pollBatch}
 *       (its own short transaction, applied by Spring Data's repository proxy)</li>
 *   <li>each claimed message is dispatched <em>outside</em> any transaction - a network call has no
 *       business holding a DB connection/lock open</li>
 *   <li>the outcome is recorded via {@link OutboxOutcomeRecorder} (its own short transaction per
 *       message, so one failure never rolls back another message's success)</li>
 * </ol>
 */
public class OutboxProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessingService.class);

    private final OutboxMessageRepository repository;
    private final OutboxDispatcherRegistry dispatcherRegistry;
    private final OutboxOutcomeRecorder outcomeRecorder;
    private final OutboxMetrics metrics;
    private final OutboxProperties properties;
    private final String lockOwner;

    public OutboxProcessingService(OutboxMessageRepository repository,
                                    OutboxDispatcherRegistry dispatcherRegistry,
                                    OutboxOutcomeRecorder outcomeRecorder,
                                    OutboxMetrics metrics,
                                    OutboxProperties properties) {
        this.repository = repository;
        this.dispatcherRegistry = dispatcherRegistry;
        this.outcomeRecorder = outcomeRecorder;
        this.metrics = metrics;
        this.properties = properties;
        this.lockOwner = resolveLockOwner(properties.getPolling().getLockOwner());
    }

    /** @return how many messages were claimed (and thus attempted) this cycle */
    public int processBatch() {
        List<OutboxMessage> claimed = repository.pollBatch(
                properties.getPolling().getBatchSize(), Instant.now(), lockOwner);
        for (OutboxMessage message : claimed) {
            processOne(message);
        }
        return claimed.size();
    }

    private void processOne(OutboxMessage message) {
        Optional<OutboxDispatcher> dispatcher = dispatcherRegistry.find(message.getTransport());
        if (dispatcher.isEmpty()) {
            outcomeRecorder.recordFailure(message.getId(),
                    "No OutboxDispatcher registered for transport '" + message.getTransport() + "'", false);
            return;
        }

        Instant start = Instant.now();
        DispatchResult result;
        try {
            result = dispatcher.get().dispatch(message);
        } catch (Exception e) {
            log.warn("Dispatch threw for outbox message {}", message.getId(), e);
            result = DispatchResult.failure(String.valueOf(e.getMessage()), true);
        }
        metrics.recordDispatchDuration(message.getTransport(), message.getDestination(), Duration.between(start, Instant.now()));

        if (result instanceof DispatchResult.Success) {
            outcomeRecorder.recordSuccess(message.getId());
        } else if (result instanceof DispatchResult.Failure failure) {
            outcomeRecorder.recordFailure(message.getId(), failure.reason(), failure.retryable());
        }
    }

    private static String resolveLockOwner(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
