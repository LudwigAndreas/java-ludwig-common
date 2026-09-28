package ru.ludwigandreas.job.core.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ScheduleSpecTest {

    @Test
    void fixedDelayDefaultsTheFirstRunToOneWholeDelayAfterStartup() {
        ScheduleSpec spec = ScheduleSpec.fixedDelay(Duration.ofSeconds(30));

        assertThat(spec.fixedDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(spec.initialDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(spec.isCron()).isFalse();
    }

    @Test
    void cronSpecReportsItselfAsCron() {
        ScheduleSpec spec = ScheduleSpec.cron("0 0 */4 * * *");

        assertThat(spec.isCron()).isTrue();
        assertThat(spec.fixedDelay()).isNull();
    }

    @Test
    void rejectsASpecThatNamesBothTriggers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScheduleSpec(Duration.ofSeconds(5), "0 0 * * * *", Duration.ZERO))
                .withMessageContaining("Exactly one");
    }

    @Test
    void rejectsASpecThatNamesNeitherTrigger() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScheduleSpec(null, null, Duration.ZERO))
                .withMessageContaining("Exactly one");
    }

    @Test
    void rejectsAnUnparseableCronExpression() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleSpec.cron("every tuesday please"))
                .withMessageContaining("cron");
    }

    @Test
    void rejectsANonPositiveFixedDelay() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleSpec.fixedDelay(Duration.ZERO))
                .withMessageContaining("fixed-delay");
    }

    @Test
    void rejectsANegativeInitialDelay() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleSpec.fixedDelay(Duration.ofSeconds(5), Duration.ofSeconds(-1)))
                .withMessageContaining("initial-delay");
    }
}
