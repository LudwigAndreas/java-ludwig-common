package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.messaging.dlt.DeadLetterTemplates;

/**
 * The regression test for a defect that was live in the reference implementation.
 *
 * <p>A {@code DeadLetterPublishingRecoverer} republishes the original {@code byte[]} when the failure was a
 * deserialization failure - which is right, because bytes that could not be parsed are the only faithful
 * representation of that record. An application template configured with a {@code StringSerializer} cannot
 * send them: the publication fails with {@code Can't convert value of class [B}, the offset is committed
 * anyway, and the poison record is lost. In the one case the dead-letter topic exists for.
 *
 * <p>It was invisible because nothing had ever put a malformed record on that topic in a test with a real
 * broker - a dead-letter topic nobody has ever seen a record arrive on is indistinguishable from one that
 * works. {@code SharedConsumerIT} now asserts the arrival; this asserts the serialization directly, which is
 * the part that was wrong.
 */
class DeadLetterTemplatesTest {

    private final Serializer<Object> serializer = valueSerializer();

    @Test
    @DisplayName("raw bytes pass through, which is what a poison record's value is")
    void serializesRawBytes() {
        assertThat(serializer.serialize("t", "bytes".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a text payload goes as text rather than as a quoted JSON string")
    void serializesText() {
        assertThat(serializer.serialize("t", "text")).isEqualTo("text".getBytes(StandardCharsets.UTF_8));
    }

    /** An ordinary listener failure hands the recoverer the deserialized object, not bytes. */
    @Test
    @DisplayName("anything else is serialized as JSON, which is what it was on the wire")
    void serializesObjectsAsJson() {
        assertThat(new String(serializer.serialize("t", Map.of("a", 1)), StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("the key is serialized by the same delegating serializer")
    void serializesKeysTheSameWay() {
        KafkaTemplate<Object, Object> template = template();
        assertThat(template.getProducerFactory().getKeySerializer())
                .isSameAs(template.getProducerFactory().getValueSerializer());
    }

    @SuppressWarnings("unchecked")
    private static Serializer<Object> valueSerializer() {
        return (Serializer<Object>) template().getProducerFactory().getValueSerializer();
    }

    private static KafkaTemplate<Object, Object> template() {
        return DeadLetterTemplates.create(new KafkaProperties(), new ObjectMapper());
    }
}
