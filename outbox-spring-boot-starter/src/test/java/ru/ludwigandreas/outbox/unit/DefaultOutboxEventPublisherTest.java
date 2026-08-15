package ru.ludwigandreas.outbox.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.audit.OutboxAuditLogger;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.metrics.NoopOutboxMetrics;
import ru.ludwigandreas.outbox.publisher.DefaultOutboxEventPublisher;
import ru.ludwigandreas.outbox.publisher.filter.OutboxPublishFilter;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.routing.OutboxRoute;
import ru.ludwigandreas.outbox.routing.OutboxRouteResolver;
import ru.ludwigandreas.outbox.serialization.OutboxPayloadSerializer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultOutboxEventPublisherTest {

    private OutboxMessageRepository repository;
    private OutboxPayloadSerializer serializer;
    private OutboxRouteResolver routeResolver;
    private OutboxAuditLogger auditLogger;
    private OutboxProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxMessageRepository.class);
        serializer = mock(OutboxPayloadSerializer.class);
        routeResolver = mock(OutboxRouteResolver.class);
        auditLogger = mock(OutboxAuditLogger.class);
        properties = new OutboxProperties();
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(serializer.serialize(any())).thenReturn("{}");
        when(routeResolver.resolve(any())).thenReturn(new OutboxRoute("KAFKA", "orders-topic"));
    }

    private DefaultOutboxEventPublisher publisher(OutboxPublishFilter filter) {
        return new DefaultOutboxEventPublisher(repository, serializer, filter, routeResolver, properties, auditLogger, new NoopOutboxMetrics());
    }

    private static OutboxEvent.OutboxEventBuilder eventBuilder() {
        return OutboxEvent.builder().aggregateType("Order").aggregateId("1").eventType("OrderCreated").payload(Map.of("id", "1"));
    }

    @Test
    void filteredEventIsSkippedWithoutTouchingRepository() {
        DefaultOutboxEventPublisher publisher = publisher(event -> false);

        Optional<OutboxMessage> result = publisher.publish(eventBuilder().build());

        assertThat(result).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void idempotentRepublishReturnsExistingRowWithoutInserting() {
        OutboxMessage existing = new OutboxMessage();
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
        DefaultOutboxEventPublisher publisher = publisher(event -> true);

        Optional<OutboxMessage> result = publisher.publish(eventBuilder().idempotencyKey("key-1").build());

        assertThat(result).contains(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publishesAndPopulatesMessageFromResolvedRoute() {
        DefaultOutboxEventPublisher publisher = publisher(event -> true);

        Optional<OutboxMessage> result = publisher.publish(eventBuilder().build());

        assertThat(result).isPresent();
        OutboxMessage saved = result.get();
        assertThat(saved.getTransport()).isEqualTo("KAFKA");
        assertThat(saved.getDestination()).isEqualTo("orders-topic");
        assertThat(saved.getPayload()).isEqualTo("{}");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getMaxAttempts()).isEqualTo(properties.getRetry().getMaxAttempts());
        verify(auditLogger).onTransition(eq(saved), isNull(), eq(OutboxStatus.PENDING), any());
    }

    @Test
    void orderingDisabledClearsOrderingKey() {
        properties.getOrdering().setEnabled(false);
        DefaultOutboxEventPublisher publisher = publisher(event -> true);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        publisher.publish(eventBuilder().orderingKey("K1").build());

        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderingKey()).isNull();
    }

    @Test
    void publishAllSkipsFilteredEventsButKeepsOrderOfTheRest() {
        DefaultOutboxEventPublisher publisher = publisher(event -> !"Skip".equals(event.getEventType()));

        List<OutboxMessage> result = publisher.publishAll(List.of(
                eventBuilder().eventType("Keep1").build(),
                eventBuilder().eventType("Skip").build(),
                eventBuilder().eventType("Keep2").build()));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(OutboxMessage::getEventType).containsExactly("Keep1", "Keep2");
    }
}
