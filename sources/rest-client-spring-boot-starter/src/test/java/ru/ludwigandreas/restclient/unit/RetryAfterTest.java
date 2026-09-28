package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.restclient.resilience.RetryAfter;

/** Both forms of {@code Retry-After}, the cap, and everything malformed. */
class RetryAfterTest {

    private static final Duration MAX = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("delta-seconds")
    void parsesDeltaSeconds() {
        assertThat(RetryAfter.millis("5", MAX, CLOCK)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("an HTTP-date is converted to a wait from now")
    void parsesHttpDate() {
        String date = ZonedDateTime.ofInstant(NOW.plusSeconds(12), ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(RetryAfter.millis(date, MAX, CLOCK)).isEqualTo(12_000);
    }

    @Test
    @DisplayName("an HTTP-date in the past means now, not a negative wait")
    void clampsPastDatesToZero() {
        String date = ZonedDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(RetryAfter.millis(date, MAX, CLOCK)).isZero();
    }

    @Test
    @DisplayName("a hostile value cannot park a thread for longer than the cap")
    void capsAtMaxRetryAfter() {
        assertThat(RetryAfter.millis("3600", MAX, CLOCK)).isEqualTo(MAX.toMillis());
    }

    @Test
    @DisplayName("absent or unparseable means 'the peer said nothing', not 'retry immediately'")
    void reportsAbsenceDistinctlyFromZero() {
        assertThat(RetryAfter.millis(null, MAX, CLOCK)).isEqualTo(-1);
        assertThat(RetryAfter.millis("  ", MAX, CLOCK)).isEqualTo(-1);
        assertThat(RetryAfter.millis("soon", MAX, CLOCK)).isEqualTo(-1);
        assertThat(RetryAfter.millis("-5", MAX, CLOCK)).isEqualTo(-1);
    }
}
