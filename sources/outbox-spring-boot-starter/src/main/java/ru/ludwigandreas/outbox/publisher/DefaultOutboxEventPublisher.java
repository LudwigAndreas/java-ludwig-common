package ru.ludwigandreas.outbox.publisher;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.outbox.api.OutboxEventTypes;
import ru.ludwigandreas.outbox.audit.OutboxAuditLogger;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;
import ru.ludwigandreas.outbox.publisher.filter.OutboxPublishFilter;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.routing.OutboxRoute;
import ru.ludwigandreas.outbox.routing.OutboxRouteResolver;
import ru.ludwigandreas.outbox.serialization.OutboxPayloadSerializer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Transactional(propagation = Propagation.MANDATORY)
public class DefaultOutboxEventPublisher implements OutboxEventPublisher {

    private final OutboxMessageRepository repository;
    private final OutboxPayloadSerializer payloadSerializer;
    private final OutboxPublishFilter publishFilter;
    private final OutboxRouteResolver routeResolver;
    private final OutboxProperties properties;
    private final OutboxAuditLogger auditLogger;
    private final OutboxMetrics metrics;
    private final CorrelationProvider correlation;
    private final String correlationHeader;
    private final OutboxEventTypes eventTypes;

    /**
     * Creates the publisher.
     *
     * @param repository        the outbox table
     * @param payloadSerializer turns the payload into the stored JSON
     * @param publishFilter     decides whether an event is published at all
     * @param routeResolver     picks the transport and destination
     * @param properties        the outbox configuration
     * @param auditLogger       records the state transition
     * @param metrics           counts publications
     * @param correlation       the current request's correlation id, captured into the message's headers
     * @param correlationHeader the header name it is stored under, the name consumers read
     * @param eventTypes        the declared payload class of each event type
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a collaborator list, assembled once by the autoconfiguration.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DefaultOutboxEventPublisher(OutboxMessageRepository repository,
                                        OutboxPayloadSerializer payloadSerializer,
                                        OutboxPublishFilter publishFilter,
                                        OutboxRouteResolver routeResolver,
                                        OutboxProperties properties,
                                        OutboxAuditLogger auditLogger,
                                        OutboxMetrics metrics,
                                        CorrelationProvider correlation,
                                        String correlationHeader,
                                        OutboxEventTypes eventTypes) {
        this.repository = repository;
        this.payloadSerializer = payloadSerializer;
        this.publishFilter = publishFilter;
        this.routeResolver = routeResolver;
        this.properties = properties;
        this.auditLogger = auditLogger;
        this.metrics = metrics;
        this.correlation = correlation;
        this.correlationHeader = correlationHeader;
        this.eventTypes = eventTypes;
    }

    @Override
    public Optional<OutboxMessage> publish(OutboxEvent event) {
        Objects.requireNonNull(event.getAggregateType(), "OutboxEvent.aggregateType must not be null");
        Objects.requireNonNull(event.getAggregateId(), "OutboxEvent.aggregateId must not be null");
        Objects.requireNonNull(event.getEventType(), "OutboxEvent.eventType must not be null");
        Objects.requireNonNull(event.getPayload(), "OutboxEvent.payload must not be null");
        if (properties.getEvents().isRequireDeclared()) {
            requireDeclared(event);
        }

        if (!publishFilter.shouldPublish(event)) {
            metrics.recordFiltered(event.getEventType());
            return Optional.empty();
        }

        boolean idempotencyKeyed = properties.getIdempotency().isEnabled() && event.getIdempotencyKey() != null;
        if (idempotencyKeyed) {
            Optional<OutboxMessage> existing = repository.findByIdempotencyKey(event.getIdempotencyKey());
            if (existing.isPresent()) {
                return existing;
            }
        }

        OutboxRoute route = routeResolver.resolve(event);
        String payload = payloadSerializer.serialize(event.getPayload());

        OutboxMessage message = new OutboxMessage();
        message.setAggregateType(event.getAggregateType());
        message.setAggregateId(event.getAggregateId());
        message.setEventType(event.getEventType());
        message.setEventVersion(event.getEventVersion());
        message.setPayload(payload);
        message.setHeaders(headersOf(event));
        message.setOrderingKey(properties.getOrdering().isEnabled() ? event.getOrderingKey() : null);
        message.setTransport(route.transport());
        message.setDestination(route.destination());
        message.setIdempotencyKey(idempotencyKeyed ? event.getIdempotencyKey() : null);
        message.setStatus(OutboxStatus.PENDING);
        message.setAttempts(0);
        message.setMaxAttempts(properties.getRetry().getMaxAttempts());
        message.setNextAttemptAt(Instant.now());
        message.setTraceId(event.getTraceId());

        // Flushed immediately (rather than deferred to the caller's eventual commit) so an idempotency-key
        // race surfaces synchronously here, at the call site, instead of at an unrelated later flush point.
        OutboxMessage saved = repository.saveAndFlush(message);
        auditLogger.onTransition(saved, null, OutboxStatus.PENDING, "published");
        metrics.recordPublished(event.getEventType(), route.transport());
        return Optional.of(saved);
    }

    /**
     * Refuses an event whose type is not declared, or whose payload is not the declared class: such an event
     * would be published without anything able to describe it.
     */
    private void requireDeclared(OutboxEvent event) {
        OutboxEventTypes.Declaration declared = Optional.ofNullable(eventTypes.declarations().get(event.getEventType()))
                .orElseThrow(() -> new IllegalStateException("Outbox event type '" + event.getEventType()
                        + "' is not declared in any OutboxEventTypes bean, and ludwig.outbox.events.require-declared"
                        + " is on"));
        if (!declared.payload().isInstance(event.getPayload())) {
            throw new IllegalStateException("Outbox event type '" + event.getEventType() + "' is declared with payload "
                    + declared.payload().getName() + " but was published with "
                    + event.getPayload().getClass().getName());
        }
        if (!Objects.equals(declared.route(), event.getRoute())) {
            throw new IllegalStateException("Outbox event type '" + event.getEventType() + "' is declared with route "
                    + declared.route() + " but was published with route " + event.getRoute());
        }
    }

    /**
     * The event's headers, plus the publishing request's correlation id unless the event set one.
     *
     * <p>Captured here because this is the last moment the request's context exists: the message is
     * dispatched later, on the poller's thread, which has none. A header the caller set wins, because the
     * caller may be relaying an id from further upstream.
     */
    private Map<String, String> headersOf(OutboxEvent event) {
        Map<String, String> headers = event.getHeaders() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(event.getHeaders());
        if (!headers.containsKey(correlationHeader)) {
            correlation.currentCorrelationId().ifPresent(id -> headers.put(correlationHeader, id));
        }
        return headers.isEmpty() ? event.getHeaders() : headers;
    }

    @Override
    public List<OutboxMessage> publishAll(List<OutboxEvent> events) {
        List<OutboxMessage> result = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            publish(event).ifPresent(result::add);
        }
        return result;
    }
}
