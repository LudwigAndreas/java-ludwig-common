package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.usersettings.wellknown.QuietHours;

/** The window that wraps past midnight, which is the normal case and the easy one to get backwards. */
class QuietHoursTest {

    @Test
    @DisplayName("a window that wraps past midnight covers the night, not the day")
    void window_wrapping_midnight_covers_the_night() {
        QuietHours night = new QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(night.covers(LocalTime.of(23, 0))).isTrue();
        assertThat(night.covers(LocalTime.of(3, 0))).isTrue();
        assertThat(night.covers(LocalTime.of(22, 0))).isTrue();
        assertThat(night.covers(LocalTime.of(12, 0))).isFalse();
        assertThat(night.covers(LocalTime.of(7, 0))).isFalse();
    }

    @Test
    @DisplayName("a window within one day covers only that span")
    void window_within_a_day_covers_that_span() {
        QuietHours lunch = new QuietHours(true, LocalTime.of(12, 0), LocalTime.of(13, 0));

        assertThat(lunch.covers(LocalTime.of(12, 30))).isTrue();
        assertThat(lunch.covers(LocalTime.of(13, 0))).isFalse();
        assertThat(lunch.covers(LocalTime.of(23, 0))).isFalse();
    }

    @Test
    @DisplayName("a disabled window covers nothing, and keeps the times the subject chose")
    void disabled_window_covers_nothing() {
        QuietHours disabled = QuietHours.disabled();

        assertThat(disabled.covers(LocalTime.of(23, 0))).isFalse();
        assertThat(disabled.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(disabled.end()).isEqualTo(LocalTime.of(7, 0));
    }

    @Test
    @DisplayName("a zero-length window covers nothing rather than everything")
    void zero_length_window_covers_nothing() {
        QuietHours degenerate = new QuietHours(true, LocalTime.of(9, 0), LocalTime.of(9, 0));

        assertThat(degenerate.covers(LocalTime.of(9, 0))).isFalse();
        assertThat(degenerate.covers(LocalTime.of(21, 0))).isFalse();
    }
}
