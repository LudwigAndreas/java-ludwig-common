package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.messaging.error.UnsupportedEventVersionException;
import ru.ludwigandreas.messaging.serialization.VersionGatingDeserializer;

/**
 * The version gate, and specifically that it gates <em>before</em> the payload is parsed.
 *
 * <p>Every test here counts delegate invocations rather than only asserting the exception, because "the
 * record was refused" and "the record was refused after being deserialized into a type that no longer
 * matches it" are the two outcomes this class exists to distinguish.
 */
class VersionGatingDeserializerTest {

    private final AtomicInteger delegateCalls = new AtomicInteger();

    private final Deserializer<String> delegate = new Deserializer<>() {
        @Override
        public String deserialize(String topic, byte[] data) {
            delegateCalls.incrementAndGet();
            return new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(String topic, Headers headers, byte[] data) {
            return deserialize(topic, data);
        }
    };

    @Test
    @DisplayName("deserializes a record inside the accepted range")
    void acceptsAnInRangeVersion() {
        VersionGatingDeserializer<String> gate = new VersionGatingDeserializer<>(delegate, 1, 2);

        assertThat(gate.deserialize("orders", headers("2"), payload())).isEqualTo("{}");
        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    @DisplayName("refuses a version above the range without deserializing it")
    void refusesAVersionAboveTheRange() {
        VersionGatingDeserializer<String> gate = new VersionGatingDeserializer<>(delegate, 1, 2);

        assertThatThrownBy(() -> gate.deserialize("orders", headers("3"), payload()))
                .isInstanceOf(UnsupportedEventVersionException.class)
                .hasMessageContaining("event version 3")
                .hasMessageContaining("1..2");
        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    @DisplayName("refuses a version below the range without deserializing it")
    void refusesAVersionBelowTheRange() {
        VersionGatingDeserializer<String> gate = new VersionGatingDeserializer<>(delegate, 2, 3);

        assertThatThrownBy(() -> gate.deserialize("orders", headers("1"), payload()))
                .isInstanceOf(UnsupportedEventVersionException.class);
        assertThat(delegateCalls).hasValue(0);
    }

    /**
     * A record produced before the header existed is version 1, which is what {@code OutboxEvent} defaults
     * to on the producing side - so a consumer accepting version 1 accepts it.
     */
    @Test
    @DisplayName("a record with no version header is version 1")
    void treatsAMissingHeaderAsVersionOne() {
        VersionGatingDeserializer<String> gate = new VersionGatingDeserializer<>(delegate, 1, 1);

        assertThat(gate.deserialize("orders", new RecordHeaders(), payload())).isEqualTo("{}");
        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    @DisplayName("reads the pre-prefix version header when the canonical one is absent")
    void readsTheLegacyVersionHeader() {
        VersionGatingDeserializer<String> gate = new VersionGatingDeserializer<>(delegate, 1, 1);
        RecordHeaders headers = new RecordHeaders();
        headers.add(ru.ludwigandreas.messaging.api.MessageHeaders.LEGACY_EVENT_VERSION,
                "4".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> gate.deserialize("orders", headers, payload()))
                .isInstanceOf(UnsupportedEventVersionException.class);
        assertThat(delegateCalls).hasValue(0);
    }

    /**
     * Unlike an absent header, an unparseable one is refused. A version header nobody can parse is a
     * producer bug, and the failure mode of guessing is exactly what this class exists to prevent.
     */
    @Test
    @DisplayName("an unparseable version header is refused rather than defaulted")
    void refusesAnUnparseableVersion() {
        VersionGatingDeserializer<String> gate = new VersionGatingDeserializer<>(delegate, 1, 99);

        assertThatThrownBy(() -> gate.deserialize("orders", headers("v2"), payload()))
                .isInstanceOf(UnsupportedEventVersionException.class);
        assertThat(delegateCalls).hasValue(0);
    }

    /**
     * The two-argument form has no headers to read and therefore cannot gate. It delegates rather than
     * refusing, because refusing would break every path that deserializes a value outside a consumer poll.
     */
    @Test
    @DisplayName("the header-less overload delegates, because it has nothing to gate on")
    void delegatesWhenThereAreNoHeaders() {
        VersionGatingDeserializer<String> gate = new VersionGatingDeserializer<>(delegate, 5, 5);

        assertThat(gate.deserialize("orders", payload())).isEqualTo("{}");
        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    @DisplayName("an inverted range is refused at construction, not at the first record")
    void refusesAnInvertedRange() {
        assertThatThrownBy(() -> new VersionGatingDeserializer<>(delegate, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3..2");
    }

    private static Headers headers(String version) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(ru.ludwigandreas.messaging.api.MessageHeaders.EVENT_VERSION,
                version.getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    private static byte[] payload() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }
}
