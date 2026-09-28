package ru.ludwigandreas.usersettings.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.api.InboundEnvelope;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.projection.SettingsProjectionService;
import ru.ludwigandreas.usersettings.event.SettingsEventTypes;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;

/**
 * Consumes the owner's change stream into the local replica.
 *
 * <p>The payload is taken as a raw string and deserialized here rather than through a configured
 * {@code JsonDeserializer}, and one of the two original reasons for that has gone while the other has
 * become the whole point. The reason that has gone was that it kept this module from dictating the
 * consumer factory a service already owns; the factory now comes from
 * {@code messaging-spring-boot-starter} and is the same for every consumer on the platform. The reason
 * that remains is that this listener handles three payload types on one topic, dispatching on the
 * event-type header - which a container factory fixed to a single type cannot express, whatever it is
 * fixed to.
 *
 * <p>The two failure modes are handled differently on purpose. An unparseable payload is
 * acknowledged and dropped, because replaying it will fail identically forever. A failure while
 * applying the event - the database is down, a constraint fired - is rethrown without acknowledging,
 * so the container redelivers it. Redelivery is safe because
 * {@link SettingsProjectionService#apply(UserSettingChangedEvent)} is idempotent, and a redelivery is
 * now bounded: the platform's error handler retries with growing gaps and dead-letters what it cannot
 * apply, instead of redelivering the record forever at full speed as this module's own factory did.
 *
 * <p>Dispatch is on the event-type header rather than on the payload's shape. Guessing the type from
 * which fields happen to be present works until two event types overlap, and then it silently
 * applies the wrong one; a type this consumer does not know is ignored, which is what lets the owner
 * add a fourth event type without coordinating a release.
 */
@Slf4j
@RequiredArgsConstructor
public class SettingsEventListener {

    /**
     * Header the outbox's Kafka dispatcher publishes the event type under.
     *
     * <p>This used to be a literal copied out of {@code KafkaOutboxDispatcher}, with a comment saying the
     * two had to agree and that a constant here was what made a rename in the dispatcher a search hit. The
     * comment was right about the risk and a search hit was the best it could offer. The name is now
     * {@link MessageHeaders#EVENT_TYPE}, declared once in {@code messaging-spring-boot-starter} and
     * imported by both halves, so a rename is a compile error instead.
     */
    public static final String EVENT_TYPE_HEADER = MessageHeaders.EVENT_TYPE;

    private final SettingsProjectionService projectionService;
    private final ObjectMapper objectMapper;

    /**
     * Reads the producer's envelope off the record.
     *
     * <p>The reason this listener takes a {@code ConsumerRecord} rather than a payload and two
     * {@code @Header} parameters. The event type has to be read under its canonical name <em>and</em> its
     * pre-prefix one while the producer writes both, and that is a fallback rather than a parameter -
     * expressing it as two {@code @Header} arguments left Spring Kafka unable to tell which parameter was
     * the payload, which is a container-level failure that dead-letters every record on the topic. One
     * reader that owns the fallback is the shape that works and the shape that does not have to be
     * repeated in the next consumer.
     */
    private final EnvelopeReader envelopeReader;

    /**
     * One record: dispatched on its event-type header, acknowledged whatever the outcome.
     *
     * <p>No {@code Acknowledgment} parameter, and that is not a simplification - it is required. The
     * platform's container acknowledges per record, and Spring Kafka supplies an {@code Acknowledgment}
     * only in a manual ack mode; a listener that declares one anyway has that parameter resolved as its
     * payload instead, and every record then fails with "Payload value must not be empty". The commit
     * behaviour is unchanged: a record that reaches the end of this method is committed by the container,
     * and one whose application threw propagates out and leaves the offset uncommitted - which is what the
     * manual acknowledgement was for, and what per-record acknowledgement preserves.
     *
     * <p>The event type comes off {@link InboundEnvelope} rather than from an {@code @Header} parameter,
     * because reading it is a fallback rather than a lookup: a topic's retention outlives a release, so the
     * producer writes both the canonical {@code ludwig-event-type} and the pre-prefix {@code event-type}
     * during the transition, and this consumer may be asked to replay from before the producer was upgraded,
     * when only the legacy name is there. Reading only one of them would make a record silently ignored as an
     * unknown type - not an error anywhere, and the replica simply never learns about that change.
     * {@link MessageHeaders} states when each half can be dropped.
     *
     * <p>The log line names the record's coordinates rather than its key. The key is a tenant and a subject,
     * so it identifies a person; the coordinates identify the record, which is what an operator needs to find
     * it on the topic and on the dead-letter topic.
     */
    @KafkaListener(
            topics = "${ludwig.user-settings.projection.topic:user.settings}",
            groupId = "${ludwig.user-settings.projection.group-id:"
                    + "${spring.application.name:service}-user-settings-projection}",
            containerFactory = "userSettingsKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        InboundEnvelope<String> envelope = envelopeReader.read(record);
        String eventType = envelope.eventType();
        try {
            dispatch(envelope.payload(), eventType);
        } catch (UnreadableEventException e) {
            // Neither the payload nor the parser's message is logged. The payload carries a person's
            // settings or a consent record, and a parser's message routinely quotes the fragment it
            // choked on - so both are value leaks into a log. The topic key and the failure's type are
            // what an operator needs to find the record on the topic.
            log.error("Discarding unparseable {} event at {}: {}",
                    eventType, envelope.coordinates(), e.getCause().getClass().getSimpleName());
        }
    }

    private void dispatch(String payload, String eventType) {
        if (eventType == null) {
            log.warn("Discarding settings event with no {} header", EVENT_TYPE_HEADER);
            return;
        }
        switch (eventType) {
            case SettingsEventTypes.USER_SETTING_CHANGED ->
                    projectionService.apply(read(payload, UserSettingChangedEvent.class));
            case SettingsEventTypes.CONSENT_GRANTED, SettingsEventTypes.CONSENT_REVOKED ->
                    projectionService.apply(read(payload, ConsentChangedEvent.class));
            default -> log.debug("Ignoring settings event of unknown type {}", eventType);
        }
    }

    /**
     * Parses a payload, turning a parse failure into an unchecked one.
     *
     * <p>Jackson's own exception is checked, because it extends {@code IOException} - a payload that
     * is not JSON is not an I/O problem, and catching it as one in the listener would make the catch
     * block read as though the broker had gone away. Converting here means the listener catches
     * exactly one thing, named for what it is, and the failure it must <em>not</em> swallow - an
     * apply that threw - stays uncaught and is redelivered.
     */
    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception e) {
            throw new UnreadableEventException(type.getSimpleName(), e);
        }
    }

    /** A payload that will never parse, however many times it is redelivered. */
    static class UnreadableEventException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnreadableEventException(String expectedType, Throwable cause) {
            // The cause's message is deliberately not folded into this one: it quotes the payload.
            super("not a readable " + expectedType, cause);
        }
    }

}
