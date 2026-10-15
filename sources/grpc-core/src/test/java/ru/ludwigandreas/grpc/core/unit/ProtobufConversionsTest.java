package ru.ludwigandreas.grpc.core.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.grpc.core.mapping.ProtobufConversions;

class ProtobufConversionsTest {

    @Test
    @DisplayName("an instant survives a round trip through a timestamp, to the nanosecond")
    void instants() {
        Instant instant = Instant.parse("2026-10-15T10:11:12.123456789Z");

        assertThat(ProtobufConversions.toInstant(ProtobufConversions.toTimestamp(instant))).isEqualTo(instant);
    }

    @Test
    @DisplayName("a duration survives a round trip, to the nanosecond")
    void durations() {
        Duration duration = Duration.ofSeconds(90, 5);

        assertThat(ProtobufConversions.toJavaDuration(ProtobufConversions.toDuration(duration))).isEqualTo(duration);
    }

    @Test
    @DisplayName("null stays null in every direction")
    void nulls() {
        assertThat(ProtobufConversions.toTimestamp(null)).isNull();
        assertThat(ProtobufConversions.toInstant(null)).isNull();
        assertThat(ProtobufConversions.toDuration(null)).isNull();
        assertThat(ProtobufConversions.toJavaDuration(null)).isNull();
    }
}
