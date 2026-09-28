package ru.ludwigandreas.notification.messaging;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.ludwigandreas.notification.service.NotificationService;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.IngressSource;
import ru.ludwigandreas.notification.service.model.NotificationCommand;
import ru.ludwigandreas.notification.service.model.NotificationRequestView;
import ru.ludwigandreas.notification.service.model.Priority;
import ru.ludwigandreas.notification.service.model.RecipientKind;
import ru.ludwigandreas.notification.service.model.RecipientRef;
import ru.ludwigandreas.observability.correlation.CorrelationContext;

/**
 * The primary ingress: fire-and-forget requests from every other service on the platform.
 *
 * <p>It translates the record into a {@link NotificationCommand} and calls the application service.
 * It validates nothing beyond what deserialization enforces, resolves nobody, and writes no row -
 * because everything it might decide is also decided by the REST controller, and the only way to keep
 * two adapters genuinely identical is for neither of them to decide anything.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Delivery here is at-least-once by construction: a rebalance, a failed commit or an offset reset
 * all redeliver. The dedup key is taken from the payload, falling back to the record key, and the
 * idempotency store is what makes a redelivery a no-op. A record carrying neither is still processed -
 * a producer that sends no key has accepted a duplicate on redelivery, and refusing it outright would
 * lose a notification that somebody wanted.
 *
 * <h2>Why nothing is caught</h2>
 *
 * <p>Except for the one case below, exceptions propagate. A listener that swallows a failure
 * acknowledges the record as processed, so a transient database outage would silently discard every
 * notification that arrived during it. Letting it escape means the container retries and, once its
 * own retry budget is spent, dead-letters - which is recoverable, unlike a silent drop.
 *
 * <p>The exception is a request nothing can ever be done with - no recipient resolvable, a template
 * that does not exist. Those are recorded as a {@code REJECTED} request and acknowledged, because
 * replaying a permanently un-sendable message forever is not a retry, it is a loop.
 */
@Slf4j
@Component
// Conditional on the same switch as the container factory it names. Without this the listener bean
// is created whatever the configuration says, and a deployment with the Kafka ingress switched off -
// a REST-only environment, or any of this service's own tests - fails to start on a missing
// container factory rather than simply not consuming.
@ConditionalOnProperty(prefix = "ludwig.notification.ingress", name = "kafka-enabled",
        matchIfMissing = true)
@RequiredArgsConstructor
public class NotificationRequestListener {

    private final NotificationService notificationService;
    private final CorrelationContext correlationContext;

    /**
     * Consumes one request.
     *
     * <p>The correlation id is bound for the duration from the record header the observability
     * starter's Kafka interceptor put there, so every log line about this request - and, later, the
     * delivery it becomes - joins back to whatever triggered it in the calling service.
     */
    @KafkaListener(
            topics = "${ludwig.notification.ingress.topic}",
            groupId = "${ludwig.notification.ingress.group-id}",
            concurrency = "${ludwig.notification.ingress.concurrency}",
            containerFactory = "notificationRequestListenerContainerFactory")
    public void onRequest(ConsumerRecord<String, NotificationRequestMessage> record) {
        NotificationRequestMessage message = record.value();
        if (message == null) {
            // A tombstone on this topic means nothing - there is no keyed state here to delete - so
            // it is acknowledged rather than retried forever.
            log.debug("Ignoring a null record at {}-{}@{}",
                    record.topic(), record.partition(), record.offset());
            return;
        }

        String correlationId = correlationContext.currentId().orElse(null);
        try (CorrelationContext.Scope ignored = correlationContext.open(correlationId)) {
            NotificationRequestView view = notificationService.submit(toCommand(message, record, correlationId));
            log.debug("Accepted request {} from {}-{}@{} ({} deliveries, duplicate={})",
                    view.id(), record.topic(), record.partition(), record.offset(),
                    view.deliveries().size(), view.duplicate());
        }
    }

    private NotificationCommand toCommand(NotificationRequestMessage message,
                                          ConsumerRecord<String, NotificationRequestMessage> record,
                                          String correlationId) {
        String idempotencyKey = message.idempotencyKey() != null && !message.idempotencyKey().isBlank()
                ? message.idempotencyKey()
                : record.key();
        return new NotificationCommand(
                idempotencyKey,
                message.templateKey(),
                message.category(),
                enumOf(CategoryClass.class, message.categoryClass(), CategoryClass.TRANSACTIONAL),
                enumOf(Priority.class, message.priority(), Priority.NORMAL),
                channels(message.channels()),
                recipients(message.recipients()),
                message.variables() == null ? Map.of() : message.variables(),
                message.scheduledAt(),
                IngressSource.KAFKA,
                correlationId,
                null);
    }

    private Set<ChannelType> channels(Set<String> names) {
        if (names == null) {
            return Set.of();
        }
        return names.stream()
                .map(name -> enumOf(ChannelType.class, name, null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private List<RecipientRef> recipients(List<NotificationRequestMessage.RecipientMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .map(this::toRef)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * One recipient, or null when the record names neither a user nor an address.
     *
     * <p>Filtered out rather than rejected, so one malformed entry in a hundred-recipient batch does
     * not discard the other ninety-nine. A record where <em>every</em> recipient is malformed produces
     * an empty list, which the application service records as a {@code REJECTED} request with a
     * reason - visible, queryable, and not replayed forever.
     */
    private RecipientRef toRef(NotificationRequestMessage.RecipientMessage message) {
        boolean byUser = message.userId() != null && !message.userId().isBlank();
        boolean byAddress = message.address() != null && !message.address().isBlank();
        if (byUser == byAddress) {
            log.warn("Discarding a recipient that names {} identifier(s)", byUser ? "two" : "no");
            return null;
        }
        return new RecipientRef(
                byUser ? RecipientKind.USER : RecipientKind.ADDRESS,
                byUser ? message.userId() : null,
                byUser ? null : message.address(),
                message.locale() == null || message.locale().isBlank()
                        ? null : Locale.forLanguageTag(message.locale()),
                message.timezone(),
                message.variables() == null ? Map.of() : message.variables());
    }

    /**
     * Parses an enum constant leniently.
     *
     * <p>An unknown value falls back rather than throwing, because the alternative is that a producer
     * adding a priority this deployment does not know about breaks every notification it sends -
     * including the ones whose priority this service does understand perfectly well.
     */
    private <E extends Enum<E>> E enumOf(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown {} '{}' on an inbound request; using {}",
                    type.getSimpleName(), value, fallback);
            return fallback;
        }
    }
}
