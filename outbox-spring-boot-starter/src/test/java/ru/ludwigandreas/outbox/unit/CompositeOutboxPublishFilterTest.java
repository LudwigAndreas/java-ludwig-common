package ru.ludwigandreas.outbox.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.publisher.filter.CompositeOutboxPublishFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeOutboxPublishFilterTest {

    private static final OutboxEvent EVENT = OutboxEvent.builder()
            .aggregateType("Order").aggregateId("1").eventType("OrderCreated").payload("p").build();

    @Test
    void allowsWhenNoFiltersRegistered() {
        CompositeOutboxPublishFilter composite = new CompositeOutboxPublishFilter(List.of());

        assertThat(composite.shouldPublish(EVENT)).isTrue();
    }

    @Test
    void rejectsWhenAnyFilterRejects() {
        CompositeOutboxPublishFilter composite = new CompositeOutboxPublishFilter(List.of(event -> true, event -> false));

        assertThat(composite.shouldPublish(EVENT)).isFalse();
    }

    @Test
    void allowsWhenAllFiltersAllow() {
        CompositeOutboxPublishFilter composite = new CompositeOutboxPublishFilter(List.of(event -> true, event -> true));

        assertThat(composite.shouldPublish(EVENT)).isTrue();
    }
}
