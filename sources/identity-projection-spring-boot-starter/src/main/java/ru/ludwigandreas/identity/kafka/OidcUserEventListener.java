package ru.ludwigandreas.identity.kafka;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;

/**
 * Consumes the OIDC provider's user topic into the local projection.
 *
 * <p>The payload is taken as a raw string and deserialized here rather than through a configured
 * {@code JsonDeserializer}. Two reasons were given for that originally and one of them no longer holds: it
 * kept the module from dictating the consumer factory a service already owns, and the factory now comes from
 * {@code messaging-spring-boot-starter} and is the platform's. What remains is that it puts deserialization
 * failures inside this method, where a malformed message can be logged and skipped.
 *
 * <p>That second reason is now a choice rather than a necessity, and it is worth saying which. A
 * container-level deserialization failure on the platform's factory is <em>not</em> retried forever: it is
 * classified non-retryable and dead-lettered on the first attempt, which is a better outcome than being
 * logged and dropped here. Moving this listener onto the typed inbound envelope is therefore a change worth
 * making, and it is deliberately a separate step from fixing the error handling - see
 * {@code IdentityKafkaAutoConfiguration}.
 *
 * <p>The two failure modes are handled differently on purpose. An unparseable payload is acknowledged and
 * dropped, because replaying it will fail identically forever. A failure while applying the event - the
 * database is down, a constraint fired - is rethrown without acknowledging, so the container redelivers
 * it. Redelivery is safe because {@link IdentityProjectionService#apply(OidcUserEvent)} is idempotent, and
 * it is now bounded: the platform's error handler retries with growing gaps and then dead-letters, rather
 * than Boot's default of ten immediate attempts followed by dropping the record.
 *
 * <p>There is no {@code Acknowledgment} parameter, and there must not be one. The platform's container
 * acknowledges per record, and Spring Kafka supplies an {@code Acknowledgment} only in a manual ack mode; a
 * listener that declares one anyway has that parameter resolved as its payload instead, and every record
 * then fails with "Payload value must not be empty". The commit behaviour is what it always was: reaching
 * the end of {@link #onMessage} commits the record, and a throw leaves the offset uncommitted.
 */
@Slf4j
@RequiredArgsConstructor
public class OidcUserEventListener {

    private final IdentityProjectionService projectionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${ludwig.identity.kafka.topic:oidc.users}",
            groupId = "${ludwig.identity.kafka.group-id:${spring.application.name:service}-identity-projection}",
            containerFactory = "identityKafkaListenerContainerFactory")
    public void onMessage(String payload) {
        OidcUserEvent event;
        try {
            event = objectMapper.readValue(payload, OidcUserEvent.class);
        } catch (JacksonException e) {
            // The payload itself is deliberately not logged - it is directory data about a person.
            log.error("Discarding unparseable user event: {}", e.getOriginalMessage());
            return;
        }

        projectionService.apply(event);
    }
}
