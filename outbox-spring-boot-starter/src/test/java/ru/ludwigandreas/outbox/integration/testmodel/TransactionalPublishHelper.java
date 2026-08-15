package ru.ludwigandreas.outbox.integration.testmodel;

import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.util.List;
import java.util.Optional;

/** Stands in for "the business service that publishes inside its own @Transactional method". */
public class TransactionalPublishHelper {

    private final OutboxEventPublisher publisher;

    public TransactionalPublishHelper(OutboxEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Transactional
    public Optional<OutboxMessage> publish(OutboxEvent event) {
        return publisher.publish(event);
    }

    @Transactional
    public List<OutboxMessage> publishAll(List<OutboxEvent> events) {
        return publisher.publishAll(events);
    }
}
