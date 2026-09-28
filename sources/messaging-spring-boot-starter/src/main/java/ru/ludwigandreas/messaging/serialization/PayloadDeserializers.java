package ru.ludwigandreas.messaging.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * The platform's JSON deserializer, with the one setting that is a security control rather than a
 * preference.
 *
 * <h2>{@code setUseTypeHeaders(false)} is not configurable, here or anywhere</h2>
 *
 * <p>{@code NotificationMessagingConfig} carried this comment and it moves here unchanged: <em>the type
 * is fixed by this consumer rather than taken from a record header: a header-driven deserializer will
 * instantiate whatever class a producer names, which is a deserialization gadget waiting to happen.</em>
 *
 * <p>What that means concretely: with type headers on, the class Jackson instantiates is chosen by
 * whoever produced the record. Anything that can write to the topic - a partner system, a misrouted
 * producer, an attacker who has reached the broker - picks a class on the consumer's classpath and
 * gets it constructed with attacker-chosen field values. The classpath of a Spring Boot service is
 * large and the gadget chains against Jackson polymorphic typing are a documented, recurring CVE
 * family; "we trust our topics" is the assumption that fails, because a topic is not an authenticated
 * caller.
 *
 * <p>So there is no property that turns it back on, and there must not be one. A consumer that
 * genuinely needs more than one payload type on a topic dispatches on
 * {@link ru.ludwigandreas.messaging.api.MessageHeaders#EVENT_TYPE} - which is a value <em>this</em>
 * consumer maps to a type it already knows, rather than a class name the producer chose.
 *
 * <p>The two consumers that took their payloads as {@code String} and parsed by hand - user-settings
 * and identity-projection - were sidestepping this question rather than answering it. Moving them onto
 * a shared typed factory must not hand them the gadget as the price of consistency, which is why the
 * setting is applied here, once, where neither can forget it.
 */
public final class PayloadDeserializers {

    /**
     * A JSON deserializer fixed to one payload type.
     *
     * @param type         the payload type this consumer expects
     * @param objectMapper the application's mapper, so a payload deserializes by the same rules the
     *                     rest of the service uses - a consumer with its own mapper is a consumer whose
     *                     date handling drifts from its REST layer's
     * @param <T>          the payload type
     * @return the deserializer, with type headers off
     */
    public static <T> JsonDeserializer<T> json(Class<T> type, ObjectMapper objectMapper) {
        // The third argument is Spring Kafka's own useHeadersIfPresent flag; false here and the
        // explicit call below are the same decision stated twice, because the constructor overload is
        // easy to change by accident and the setter is what a reader looks for.
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type, objectMapper, false);
        deserializer.setUseTypeHeaders(false);
        return deserializer;
    }

    /**
     * The key deserializer every consumer on this platform uses.
     *
     * <p>A message key is a partitioning decision - an aggregate id, a user id - and has been a string
     * on every topic here. Typing it would make the container factory's signature depend on a fact
     * nobody varies.
     *
     * @return a string deserializer
     */
    public static Deserializer<String> key() {
        return new StringDeserializer();
    }

    private PayloadDeserializers() {
    }
}
