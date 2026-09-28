package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import ru.ludwigandreas.messaging.serialization.PayloadDeserializers;

/**
 * The security control: a producer does not get to choose which class this consumer instantiates.
 *
 * <p>Asserted behaviourally rather than by reading the flag. A test that checked
 * {@code isUseTypeHeaders() == false} would pass if the setter were renamed and stop meaning anything; a
 * test that sends a record naming another type and asserts the fixed type came back is a test of the
 * property that matters.
 */
class PayloadDeserializersTest {

    /** Spring Kafka's own type-id header, which is what a header-driven deserializer would obey. */
    private static final String TYPE_ID = "__TypeId__";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The type this consumer expects. */
    record Expected(String value) {
    }

    /** A type a producer might name instead. On the classpath, which is the whole point. */
    record Attacker(String value) {
    }

    @Test
    @DisplayName("a record naming another type in a header still deserializes into this consumer's type")
    void ignoresAProducerSuppliedType() {
        JsonDeserializer<Expected> deserializer = PayloadDeserializers.json(Expected.class, objectMapper);
        Headers headers = new RecordHeaders();
        headers.add(TYPE_ID, Attacker.class.getName().getBytes(StandardCharsets.UTF_8));

        Object result = deserializer.deserialize("orders", headers,
                "{\"value\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(result).isInstanceOf(Expected.class).isNotInstanceOf(Attacker.class);
        assertThat(((Expected) result).value()).isEqualTo("x");
    }

    @Test
    @DisplayName("deserializes normally when no type header is present")
    void deserializesTheFixedType() {
        JsonDeserializer<Expected> deserializer = PayloadDeserializers.json(Expected.class, objectMapper);

        assertThat(deserializer.deserialize("orders", new RecordHeaders(),
                "{\"value\":\"y\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new Expected("y"));
    }

    @Test
    @DisplayName("the key deserializer is the platform's one string deserializer")
    void deserializesStringKeys() {
        assertThat(PayloadDeserializers.key().deserialize("orders", "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("k");
    }
}
