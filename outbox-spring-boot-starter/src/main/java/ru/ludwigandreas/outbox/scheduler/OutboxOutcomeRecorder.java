package ru.ludwigandreas.outbox.scheduler;

import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.outbox.audit.OutboxAuditLogger;
import ru.ludwigandreas.outbox.backoff.OutboxBackoffCalculator;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a dispatch outcome for one claimed message, each in its own short transaction - separate from
 * {@link OutboxProcessingService}'s claim step and invoked as a distinct Spring bean (not a self-invoked
 * method) precisely so the {@code @Transactional} here is honored: Spring's proxy-based AOP does not
 * intercept a bean calling its own methods internally.
 */
public class OutboxOutcomeRecorder {

    private final OutboxMessageRepository repository;
    private final OutboxBackoffCalculator backoffCalculator;
    private final OutboxAuditLogger auditLogger;
    private final OutboxMetrics metrics;
    private final OutboxProperties properties;

    public OutboxOutcomeRecorder(OutboxMessageRepository repository,
                                  OutboxBackoffCalculator backoffCalculator,
                                  OutboxAuditLogger auditLogger,
                                  OutboxMetrics metrics,
                                  OutboxProperties properties) {
        this.repository = repository;
        this.backoffCalculator = backoffCalculator;
        this.auditLogger = auditLogger;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Transactional
    public void recordSuccess(UUID messageId) {
        OutboxMessage message = repository.getByIdOrThrow(messageId);
        message.setStatus(OutboxStatus.PUBLISHED);
        message.setPublishedAt(Instant.now());
        message.setLastError(null);
        auditLogger.onTransition(message, OutboxStatus.PROCESSING, OutboxStatus.PUBLISHED, "dispatched");
        metrics.recordDispatchSucceeded(message.getEventType(), message.getTransport(), message.getDestination());
    }

    @Transactional
    public void recordFailure(UUID messageId, String reason, boolean retryable) {
        OutboxMessage message = repository.getByIdOrThrow(messageId);
        message.setAttempts(message.getAttempts() + 1);
        message.setLastError(reason);

        boolean exhausted = !retryable || message.getAttempts() >= message.getMaxAttempts();
        if (exhausted && properties.getDeadLetter().isEnabled()) {
            message.setStatus(OutboxStatus.DEAD_LETTER);
            auditLogger.onTransition(message, OutboxStatus.PROCESSING, OutboxStatus.DEAD_LETTER, reason);
            metrics.recordDeadLettered(message.getEventType(), message.getTransport());
        } else {
            message.setStatus(OutboxStatus.FAILED);
            message.setNextAttemptAt(Instant.now().plus(backoffCalculator.nextDelay(message.getAttempts())));
            auditLogger.onTransition(message, OutboxStatus.PROCESSING, OutboxStatus.FAILED, reason);
            metrics.recordDispatchFailed(message.getEventType(), message.getTransport(), message.getDestination());
        }
    }
}
