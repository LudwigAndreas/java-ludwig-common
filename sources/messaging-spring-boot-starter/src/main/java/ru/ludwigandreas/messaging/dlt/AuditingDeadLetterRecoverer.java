package ru.ludwigandreas.messaging.dlt;

import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.api.InboundEnvelope;
import ru.ludwigandreas.messaging.audit.DeadLetterAudit;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;

/**
 * Sends a record to its dead-letter topic, and records that it happened.
 *
 * <p>Composition rather than a subclass of {@code DeadLetterPublishingRecoverer}: that class decides
 * where a record goes and how its exception headers are built, and neither of those is what needs
 * changing. What needs adding is that the send is observable - a counter to alert on and an audit event -
 * and a decorator says exactly that without inheriting a large surface in order to reach one method.
 *
 * <h2>The send happens first</h2>
 *
 * <p>The delegate is called before anything is counted or audited, because the alternative records a
 * dead-lettering that may not have occurred. {@code DeadLetterPublishingRecoverer} waits for the send's
 * result and throws when it failed, so a failure arrives here as an exception - and that case gets its own
 * counter and its own audit action, because it is materially worse than a dead-lettered record: the record
 * is gone, the offset will be committed, and the platform believes it has a safety net it does not have.
 * {@code ludwig.messaging.dropped} should be zero at all times, and the most likely reason it is not is a
 * dead-letter topic nobody provisioned.
 *
 * <h2>Why the audit call is not wrapped in a try/catch</h2>
 *
 * <p>Because whether a sink failure fails the caller is {@code AuditFailurePolicy}'s decision, resolved
 * from configuration and applied by {@code FailurePolicyAuditSink}, which every wiring in this platform
 * puts in front of the real sink. A {@code catch} in a library overrides that decision for every
 * deployment. Here the caller is the container's error handler, and a deployment that has declared a
 * dead-lettering to be a state mutation which must not go unrecorded is entitled to have the recovery
 * fail loudly rather than proceed unaudited.
 */
@Slf4j
public class AuditingDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private final ConsumerRecordRecoverer delegate;
    private final EnvelopeReader envelopeReader;
    private final MessagingMetrics metrics;
    private final AuditSink auditSink;
    private final Clock clock;
    /** How far the logged cause chain goes; deep enough for a framework wrapper, short enough to read. */
    private static final int MAX_CHAIN_DEPTH = 6;

    private final String deadLetterSuffix;
    private final int maxAttempts;

    /**
     * Creates the recoverer.
     *
     * @param delegate        the publishing recoverer that actually sends
     * @param envelopeReader  reads the envelope back off the record, so the audit event names the event
     *                        rather than only its coordinates
     * @param metrics         where the counters go
     * @param auditSink       where the trail goes
     * @param clock           injected so a test can assert the recorded instant
     * @param deadLetterSuffix the suffix the destination is derived from, so this class names the same
     *                        topic the delegate sends to - both call
     *                        {@link ru.ludwigandreas.messaging.api.DeadLetterTopics#forTopic} on the
     *                        record's own topic, rather than one of them being told a name that was
     *                        computed for a different record
     * @param maxAttempts     the configured attempt budget, reported as the number spent - the container
     *                        does not hand the recoverer a count, and for a retryable failure the budget
     *                        is by definition what was spent
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a constructor assembled once by the container factory
    // builder. Every argument is a collaborator this class must not reach for statically.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AuditingDeadLetterRecoverer(ConsumerRecordRecoverer delegate, EnvelopeReader envelopeReader,
                                       MessagingMetrics metrics, AuditSink auditSink, Clock clock,
                                       String deadLetterSuffix, int maxAttempts) {
        this.delegate = delegate;
        this.envelopeReader = envelopeReader;
        this.metrics = metrics;
        this.auditSink = auditSink;
        this.clock = clock;
        this.deadLetterSuffix = deadLetterSuffix;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        InboundEnvelope<?> envelope = envelopeReader.read(record);
        String deadLetterTopic = DeadLetterTopics.forTopic(envelope.topic(), deadLetterSuffix);
        String exceptionType = typeOf(exception);
        int attempts = attemptsFor(exception);
        DeadLetterAudit audit = new DeadLetterAudit(
                envelope, deadLetterTopic, exceptionType, attempts, Instant.now(clock));
        try {
            delegate.accept(record, exception);
        // CHECKSTYLE.OFF: IllegalCatch - the delegate's contract is "throws when the send failed", and
        // what it throws is whatever the producer threw. The catch exists to record the worse outcome and
        // rethrow, not to handle it: a swallowed failure here is the silent drop this module exists to
        // remove.
        } catch (RuntimeException e) {
        // CHECKSTYLE.ON: IllegalCatch
            metrics.recordDropped(envelope.topic());
            // Neither the payload nor the failure's message is logged - both quote payload text. The
            // coordinates are what an operator needs to find the record, and it is still on the source
            // topic.
            log.error("Failed to dead-letter the record at {} to {} after {} attempt(s) ({}); "
                            + "the record has not been preserved",
                    envelope.coordinates(), deadLetterTopic, attempts, exceptionType);
            auditSink.record(audit.toAuditEvent(false));
            throw e;
        }
        metrics.recordDeadLettered(envelope.topic(), deadLetterTopic, exceptionType);
        // The chain of exception TYPES, and never their messages. One type is rarely enough to diagnose a
        // dead-letter - "IllegalStateException" is true of most things - and an exception message routinely
        // quotes the payload that caused it, which on this platform's topics is a person's settings or
        // directory data. The chain is the most an operator can be given without publishing the record into
        // the log; the record itself is on the dead-letter topic, which is where it should be read from.
        log.warn("Dead-lettered the record at {} to {} after {} attempt(s): {}",
                envelope.coordinates(), deadLetterTopic, attempts, chainOf(exception));
        auditSink.record(audit.toAuditEvent(true));
    }

    /**
     * How many attempts were spent.
     *
     * <p>One for a failure the handler was told not to retry - a deserialization failure or a rejected
     * event version reaches here on its first delivery, which is the point of registering it
     * non-retryable - and the full budget otherwise.
     */
    private int attemptsFor(Exception exception) {
        return isNonRetryable(exception) ? 1 : maxAttempts;
    }

    /**
     * Whether this failure is one of the two the shared handler refuses to retry.
     *
     * <p>Tested by walking the cause chain rather than by asking the handler, which does not expose the
     * decision it made. The chain matters because a deserialization failure arrives wrapped: the container
     * raises a {@code ListenerExecutionFailedException} around what {@code ErrorHandlingDeserializer}
     * recorded.
     */
    private boolean isNonRetryable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.kafka.support.serializer.DeserializationException
                    || cause instanceof org.springframework.messaging.converter.MessageConversionException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /**
     * The chain of exception types, outermost first, for the log line.
     *
     * <p>Bounded, and guarded against a cycle: a cause chain that loops is rare and a diagnostic that hangs
     * on one is not acceptable on an error path.
     */
    private static String chainOf(Exception exception) {
        StringBuilder chain = new StringBuilder();
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < MAX_CHAIN_DEPTH; depth++) {
            chain.append(depth == 0 ? "" : " <- ").append(cause.getClass().getSimpleName());
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return chain.toString();
    }

    /**
     * The failure's type, unwrapped one level past the container's own wrapper.
     *
     * <p>A counter tagged {@code ListenerExecutionFailedException} says nothing - every listener failure
     * is one - so the tag is the cause's type where there is one. Still the type and never the message:
     * see {@link DeadLetterAudit}.
     */
    private static String typeOf(Exception exception) {
        if (exception == null) {
            return "unknown";
        }
        Throwable cause = exception.getCause();
        return cause == null ? exception.getClass().getSimpleName() : cause.getClass().getSimpleName();
    }
}
