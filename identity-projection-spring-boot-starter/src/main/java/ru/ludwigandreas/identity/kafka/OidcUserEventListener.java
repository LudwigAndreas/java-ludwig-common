package ru.ludwigandreas.identity.kafka;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;

/**
 * Consumes the OIDC provider's user topic into the local projection.
 *
 * <p>The payload is taken as a raw string and deserialized here rather than through a configured
 * {@code JsonDeserializer}. That keeps the module from dictating the consumer factory a service already
 * owns, and - more importantly - it puts deserialization failures inside this method, where a malformed
 * message can be logged and skipped. A container-level deserialization failure is thrown before any
 * application code runs and, with the default error handler, is retried: one bad message and the
 * projection stops advancing for every user on that partition.
 *
 * <p>The two failure modes are handled differently on purpose. An unparseable payload is acknowledged and
 * dropped, because replaying it will fail identically forever. A failure while applying the event - the
 * database is down, a constraint fired - is rethrown without acknowledging, so the container redelivers
 * it. Redelivery is safe because {@link IdentityProjectionService#apply(OidcUserEvent)} is idempotent.
 */
@Slf4j
@RequiredArgsConstructor
public class OidcUserEventListener {

    private final IdentityProjectionService projectionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${ludwig.identity.kafka.topic:oidc.users}",
            groupId = "${ludwig.identity.kafka.group-id:${spring.application.name:service}-identity-projection}")
    public void onMessage(String payload, Acknowledgment acknowledgment) {
        OidcUserEvent event;
        try {
            event = objectMapper.readValue(payload, OidcUserEvent.class);
        } catch (JacksonException e) {
            // The payload itself is deliberately not logged - it is directory data about a person.
            log.error("Discarding unparseable user event: {}", e.getOriginalMessage());
            acknowledge(acknowledgment);
            return;
        }

        projectionService.apply(event);
        acknowledge(acknowledgment);
    }

    /** Null unless the container runs with a manual ack mode; the container then commits offsets itself. */
    private void acknowledge(Acknowledgment acknowledgment) {
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
}
