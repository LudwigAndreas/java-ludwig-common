package ru.ludwigandreas.messaging.dlt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import ru.ludwigandreas.messaging.cluster.KafkaClusterProducers;

/**
 * The producer a dead-letter send goes through, which is deliberately not the application's.
 *
 * <h2>The defect this class exists to fix</h2>
 *
 * <p>{@code DeadLetterPublishingRecoverer} republishes what it was given. For an ordinary listener failure
 * that is the deserialized payload; for a <em>deserialization</em> failure it is the original
 * {@code byte[]}, recovered from the exception - which is exactly right, because bytes that could not be
 * parsed are the only faithful representation of that record.
 *
 * <p>A template configured with a {@code StringSerializer} or a {@code JsonSerializer} cannot send those
 * bytes. The send fails with {@code SerializationException: Can't convert value of class [B}, the recoverer
 * reports a failed publication, the offset is committed anyway, and the poison record is <b>gone</b>. The
 * dead-letter topic receives nothing precisely in the one case it was built for.
 *
 * <p>This was live in {@code NotificationMessagingConfig}, which passed Boot's auto-configured
 * {@code KafkaTemplate} to its recoverer. It was invisible because nothing had ever put a malformed record
 * on that topic in a test with a real broker - a dead-letter topic nobody has ever seen a record arrive on
 * is indistinguishable from one that works.
 *
 * <h2>Why a delegating serializer rather than just bytes</h2>
 *
 * <p>A {@code ByteArraySerializer} alone would fix the poison case and break the ordinary one: a listener
 * that threw on a perfectly good record hands the recoverer a deserialized object, and a byte serializer
 * cannot send that either. {@link DelegatingByTypeSerializer} picks per value: bytes pass through, a string
 * goes as a string, and anything else is serialized as JSON - which is what the payload was on the wire
 * anyway.
 */
public final class DeadLetterTemplates {

    private DeadLetterTemplates() {
    }

    /**
     * Builds the dead-letter template for a cluster.
     *
     * <p>The bootstrap servers, the security settings and the client id all come from that cluster's
     * producer configuration, so a dead-letter send authenticates exactly as every other send to that
     * cluster does. Only the serializers are this module's, and only because the payload it carries is not
     * the payload the application produces.
     *
     * @param producers    the platform's producer seam
     * @param cluster      the cluster the dead letters go to
     * @param objectMapper the application's mapper, so a dead-lettered object is shaped like the record
     *                     it came from
     * @return the template
     */
    public static KafkaTemplate<Object, Object> create(KafkaClusterProducers producers, String cluster,
                                                      ObjectMapper objectMapper) {
        Serializer<Object> serializer = delegating(objectMapper);
        return new KafkaTemplate<>(producers.create(cluster, serializer, serializer));
    }

    private static Serializer<Object> delegating(ObjectMapper objectMapper) {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(String.class, new StringSerializer());
        delegates.put(Object.class, new JsonSerializer<>(objectMapper));
        // assignable = true, so a subclass of a mapped type is covered - without it a payload type would
        // have to be named here, which is the one thing this module cannot know.
        return new DelegatingByTypeSerializer(delegates, true);
    }
}
