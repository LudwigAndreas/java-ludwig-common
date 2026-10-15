package ru.ludwigandreas.grpc.core.mapping;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.time.Instant;

/**
 * Conversions between protobuf's well-known time types and {@code java.time}, used by
 * {@link ProtobufMapperConfig}. A {@code null} on either side stays {@code null}.
 */
public final class ProtobufConversions {

    private ProtobufConversions() {
    }

    /**
     * An instant as a timestamp.
     *
     * @param instant the instant, or {@code null}
     * @return the timestamp, or {@code null}
     */
    public static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null
                : Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    /**
     * A timestamp as an instant.
     *
     * @param timestamp the timestamp, or {@code null}
     * @return the instant, or {@code null}
     */
    public static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * A {@code java.time} duration as a protobuf duration.
     *
     * @param duration the duration, or {@code null}
     * @return the protobuf duration, or {@code null}
     */
    public static Duration toDuration(java.time.Duration duration) {
        return duration == null ? null
                : Duration.newBuilder().setSeconds(duration.getSeconds()).setNanos(duration.getNano()).build();
    }

    /**
     * A protobuf duration as a {@code java.time} duration.
     *
     * @param duration the protobuf duration, or {@code null}
     * @return the duration, or {@code null}
     */
    public static java.time.Duration toJavaDuration(Duration duration) {
        return duration == null ? null : java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
    }
}
