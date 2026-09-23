package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.preference.QuietHours;

/**
 * Quiet hours are evaluated in the recipient's own zone, and the window that people actually
 * configure wraps midnight - so the two things worth testing are the wrap and the zone.
 */
class QuietHoursTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    @DisplayName("a window that wraps midnight contains the late evening and the early morning")
    void wrappingWindowContainsBothHalves() {
        QuietHours quietHours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), BERLIN);

        assertThat(quietHours.contains(berlin(23, 30))).isTrue();
        assertThat(quietHours.contains(berlin(3, 0))).isTrue();
        assertThat(quietHours.contains(berlin(6, 59))).isTrue();
    }

    @Test
    @DisplayName("a window that wraps midnight excludes the middle of the day")
    void wrappingWindowExcludesDaytime() {
        QuietHours quietHours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), BERLIN);

        assertThat(quietHours.contains(berlin(7, 0))).isFalse();
        assertThat(quietHours.contains(berlin(14, 0))).isFalse();
        assertThat(quietHours.contains(berlin(21, 59))).isFalse();
    }

    @Test
    @DisplayName("a same-day window behaves as written")
    void sameDayWindow() {
        QuietHours quietHours = new QuietHours(LocalTime.of(13, 0), LocalTime.of(14, 0), BERLIN);

        assertThat(quietHours.contains(berlin(13, 30))).isTrue();
        assertThat(quietHours.contains(berlin(12, 59))).isFalse();
        assertThat(quietHours.contains(berlin(14, 0))).isFalse();
    }

    /**
     * The whole reason the zone travels with the window: the same instant is the middle of the night
     * in one place and the middle of the afternoon in another, and evaluating in the server's zone
     * would silence exactly the recipients who configured quiet hours most deliberately.
     */
    @Test
    @DisplayName("the same instant is quiet in one zone and not in another")
    void theZoneDecides() {
        Instant instant = ZonedDateTime.of(2026, 3, 10, 23, 30, 0, 0, BERLIN).toInstant();

        assertThat(new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), BERLIN).contains(instant))
                .isTrue();
        // 23:30 in Berlin is 07:30 the next morning in Tokyo, which is outside the same window.
        assertThat(new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), TOKYO).contains(instant))
                .isFalse();
    }

    @Test
    @DisplayName("an unconfigured window contains nothing")
    void unconfiguredContainsNothing() {
        assertThat(QuietHours.none(BERLIN).contains(berlin(3, 0))).isFalse();
        assertThat(new QuietHours(LocalTime.of(9, 0), LocalTime.of(9, 0), BERLIN).isConfigured())
                .isFalse();
    }

    @Test
    @DisplayName("the next opening is the end of the window, tomorrow when the window has wrapped")
    void nextOpeningCrossesMidnight() {
        QuietHours quietHours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), BERLIN);

        Instant lateEvening = berlin(23, 30);
        Instant opening = quietHours.nextOpening(lateEvening);
        assertThat(opening).isAfter(lateEvening);
        assertThat(ZonedDateTime.ofInstant(opening, BERLIN).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(ZonedDateTime.ofInstant(opening, BERLIN).getDayOfMonth()).isEqualTo(11);
    }

    @Test
    @DisplayName("the next opening from inside the early-morning half is the same day")
    void nextOpeningSameDay() {
        QuietHours quietHours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), BERLIN);

        Instant earlyMorning = berlin(3, 0);
        Instant opening = quietHours.nextOpening(earlyMorning);
        assertThat(ZonedDateTime.ofInstant(opening, BERLIN).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(ZonedDateTime.ofInstant(opening, BERLIN).getDayOfMonth()).isEqualTo(10);
    }

    @Test
    @DisplayName("an instant outside the window is its own next opening")
    void outsideWindowIsUnchanged() {
        QuietHours quietHours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), BERLIN);

        Instant afternoon = berlin(14, 0);
        assertThat(quietHours.nextOpening(afternoon)).isEqualTo(afternoon);
    }

    private static Instant berlin(int hour, int minute) {
        return ZonedDateTime.of(2026, 3, 10, hour, minute, 0, 0, BERLIN).toInstant();
    }
}
