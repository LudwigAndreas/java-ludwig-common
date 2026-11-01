package ru.ludwigandreas.identity.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import ru.ludwigandreas.identity.feed.IdentityProjection;

/**
 * Consumes every subscribed topic into the projection, the record's topic selecting its source.
 *
 * <p>The payload is taken as text and parsed by {@link IdentityUserEventParser}, so the two failure modes can be told
 * apart. A payload that is not an event of the contract fails as a conversion error, which the platform's consumer
 * treats as non-retryable: it is dead-lettered on its first delivery, because it will fail identically forever, and the
 * partition moves on. A failure while applying the event - the database is down, two replicas inserted the same new
 * user - is rethrown as it is and retried with backoff, then dead-lettered; applying is idempotent, so redelivery is
 * safe.
 *
 * <p>There is no {@code Acknowledgment} parameter: the platform's container acknowledges per record, and a throw
 * leaves the offset uncommitted.
 */
public class IdentityUserEventListener {

    private final IdentityProjection projection;
    private final IdentityKafkaSubscriptions subscriptions;
    private final IdentityUserEventParser parser;

    /**
     * Creates the listener.
     *
     * @param projection    the projection
     * @param subscriptions which topic feeds which source
     * @param parser        reads the events
     */
    public IdentityUserEventListener(IdentityProjection projection, IdentityKafkaSubscriptions subscriptions,
                                     IdentityUserEventParser parser) {
        this.projection = projection;
        this.subscriptions = subscriptions;
        this.parser = parser;
    }

    /**
     * Applies one event.
     *
     * @param record the record, its value an {@link IdentityUserEvent} as JSON
     */
    @KafkaListener(
            topics = "#{@identityKafkaSubscriptions.topics()}",
            groupId = "#{@identityKafkaSubscriptions.groupId()}",
            containerFactory = IdentityKafkaFeed.CONTAINER_FACTORY)
    public void onMessage(ConsumerRecord<String, String> record) {
        String source = subscriptions.sourceOf(record.topic());
        projection.apply(parser.parse(record.topic(), record.value()).toChange(source));
    }
}
