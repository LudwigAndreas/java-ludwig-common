package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalStampTest {

    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-01T11:00:00Z");

    @Test
    void aStampWithAnEarlierTimestampIsOlder() {
        assertThat(ExternalStamp.ofTimestamp(EARLIER).isOlderThan(ExternalStamp.ofTimestamp(LATER)))
                .isTrue();
    }

    @Test
    void aStampWithALaterTimestampIsNotOlder() {
        assertThat(ExternalStamp.ofTimestamp(LATER).isOlderThan(ExternalStamp.ofTimestamp(EARLIER)))
                .isFalse();
    }

    @Test
    void anIdenticalTimestampIsNotOlder() {
        assertThat(ExternalStamp.ofTimestamp(LATER).isOlderThan(ExternalStamp.ofTimestamp(LATER)))
                .isFalse();
    }

    /**
     * Version tokens are compared for equality elsewhere but never for ordering: nothing guarantees a
     * partner's tokens sort in the order it issued them, and comparing "10" against "9" as text gets
     * it backwards.
     */
    @Test
    void versionTokensNeverDecideOrdering() {
        assertThat(ExternalStamp.ofVersion("9").isOlderThan(ExternalStamp.ofVersion("10"))).isFalse();
        assertThat(ExternalStamp.ofVersion("10").isOlderThan(ExternalStamp.ofVersion("9"))).isFalse();
    }

    @Test
    void anEmptyStampIsNeverOlderThanAnything() {
        assertThat(ExternalStamp.none().isOlderThan(ExternalStamp.ofTimestamp(LATER))).isFalse();
    }

    @Test
    void nothingIsOlderThanAnEmptyStamp() {
        assertThat(ExternalStamp.ofTimestamp(EARLIER).isOlderThan(ExternalStamp.none())).isFalse();
    }

    @Test
    void anEmptyStampReportsItself() {
        assertThat(ExternalStamp.none().isEmpty()).isTrue();
        assertThat(ExternalStamp.ofVersion("v1").isEmpty()).isFalse();
    }
}
