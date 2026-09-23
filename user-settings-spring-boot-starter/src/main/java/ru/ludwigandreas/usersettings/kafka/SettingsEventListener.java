package ru.ludwigandreas.usersettings.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.projection.SettingsProjectionService;
import ru.ludwigandreas.usersettings.event.SettingsEventTypes;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;

/**
 * Consumes the owner's change stream into the local replica.
 *
 * <p>The payload is taken as a raw string and deserialized here rather than through a configured
 * {@code JsonDeserializer}, for the same two reasons {@code OidcUserEventListener} does it: it keeps
 * this module from dictating the consumer factory a service already owns, and it puts
 * deserialization failures inside this method where a malformed message can be logged and skipped. A
 * container-level deserialization failure is thrown before any application code runs and, with the
 * default error handler, is retried - so one bad message stops the projection advancing for every
 * subject on that partition.
 *
 * <p>The two failure modes are handled differently on purpose. An unparseable payload is
 * acknowledged and dropped, because replaying it will fail identically forever. A failure while
 * applying the event - the database is down, a constraint fired - is rethrown without acknowledging,
 * so the container redelivers it. Redelivery is safe because
 * {@link SettingsProjectionService#apply(UserSettingChangedEvent)} is idempotent.
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
     * Header the outbox's Kafka dispatcher publishes the event type under. Spelled exactly as
     * {@code KafkaOutboxDispatcher} writes it - the two have to agree, and a constant here is what
     * makes a rename in the dispatcher a search hit rather than a silent no-op in this consumer.
     */
    public static final String EVENT_TYPE_HEADER = "event-type";

    private final SettingsProjectionService projectionService;
    private final ObjectMapper objectMapper;

    /** One record: dispatched on its event-type header, acknowledged whatever the outcome. */
    @KafkaListener(
            topics = "${ludwig.user-settings.projection.topic:user.settings}",
            groupId = "${ludwig.user-settings.projection.group-id:"
                    + "${spring.application.name:service}-user-settings-projection}",
            containerFactory = "userSettingsKafkaListenerContainerFactory")
    public void onMessage(String payload,
                          @Header(name = EVENT_TYPE_HEADER, required = false) String eventType,
                          @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                          Acknowledgment acknowledgment) {
        try {
            dispatch(payload, eventType);
        } catch (UnreadableEventException e) {
            // Neither the payload nor the parser's message is logged. The payload carries a person's
            // settings or a consent record, and a parser's message routinely quotes the fragment it
            // choked on - so both are value leaks into a log. The topic key and the failure's type are
            // what an operator needs to find the record on the topic.
            log.error("Discarding unparseable {} event for key {}: {}",
                    eventType, key, e.getCause().getClass().getSimpleName());
        }
        acknowledge(acknowledgment);
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

    /**
     * Acknowledges a record that was applied, or deliberately dropped as stale or unparseable.
     *
     * <p>A record whose application threw never reaches this: the exception propagates out of the
     * listener, the offset is not committed, and the container redelivers. That is the point of the
     * module running its own manual-acknowledgement container - with batch acknowledgement the offset
     * would advance regardless, and a change lost to a database outage would be lost for good.
     *
     * <p>Still null-checked, because a service that overrides the container factory may hand this
     * method a container that manages offsets itself, and a listener that then threw an NPE would be
     * a worse failure than the one the override was trying to cause.
     */
    private void acknowledge(Acknowledgment acknowledgment) {
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
