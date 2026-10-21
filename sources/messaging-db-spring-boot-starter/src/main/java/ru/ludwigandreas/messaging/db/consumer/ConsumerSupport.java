package ru.ludwigandreas.messaging.db.consumer;

import java.time.Clock;
import org.springframework.kafka.listener.RecordInterceptor;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.db.settings.MessagingDbProperties;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;
import ru.ludwigandreas.messaging.settings.MessagingProperties;

/**
 * The collaborators every log container and every redirected Kafka container shares - the same instances
 * messaging's own factory builder was given, so a record counts, audits and correlates identically on
 * either transport.
 *
 * @param properties     this module's configuration
 * @param messaging      messaging's configuration, for each consumer's resolved settings
 * @param store          the log
 * @param metrics        messaging's counters
 * @param envelopeReader messaging's envelope reader, for the dead-letter audit event
 * @param auditSink      where a dead-letter is recorded
 * @param clock          the audit timestamp's clock
 * @param deadLetters    the dead-letter writer for the current mode
 * @param interceptor    every record interceptor in the context, composed; {@code null} when there is none
 */
public record ConsumerSupport(MessagingDbProperties properties, MessagingProperties messaging, LogStore store,
                              MessagingMetrics metrics, EnvelopeReader envelopeReader, AuditSink auditSink,
                              Clock clock, DeadLetterWriter deadLetters,
                              RecordInterceptor<Object, Object> interceptor) {
}
