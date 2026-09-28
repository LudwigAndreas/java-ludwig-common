package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.queue.DeliveryBackoffCalculator;

/** The curve, the ceiling, and the jitter that stops a recovering provider being stampeded. */
class DeliveryBackoffCalculatorTest {

    @Test
    @DisplayName("without jitter the delay follows the configured exponential curve")
    void exponentialWithoutJitter() {
        DeliveryBackoffCalculator calculator = calculator(0.0d, Duration.ofHours(1));

        assertThat(calculator.nextDelay(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(calculator.nextDelay(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(calculator.nextDelay(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(calculator.nextDelay(4)).isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    @DisplayName("the delay is capped at the configured maximum")
    void cappedAtMaximum() {
        DeliveryBackoffCalculator calculator = calculator(0.0d, Duration.ofMinutes(1));

        assertThat(calculator.nextDelay(20)).isEqualTo(Duration.ofMinutes(1));
    }

    /**
     * The property that matters operationally. A provider outage fails thousands of deliveries within
     * a second of each other; without jitter they all get the same delay and hit the recovering
     * provider in one synchronised burst, which fails them all again in lockstep.
     */
    @Test
    @DisplayName("jitter spreads the same attempt number across a band")
    void jitterSpreadsDelays() {
        DeliveryBackoffCalculator calculator = calculator(0.25d, Duration.ofHours(1));

        Set<Long> observed = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            observed.add(calculator.nextDelay(3).toMillis());
        }

        assertThat(observed)
                .as("200 draws of the same attempt number should not all collide")
                .hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("jitter stays inside the configured band and never goes negative")
    void jitterIsBounded() {
        DeliveryBackoffCalculator calculator = calculator(0.25d, Duration.ofHours(1));

        // Attempt 3 has a base of 40s; a +/-25% band is [30s, 50s].
        for (int i = 0; i < 500; i++) {
            Duration delay = calculator.nextDelay(3);
            assertThat(delay).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(50));
        }
    }

    @Test
    @DisplayName("attempt 0 is treated as the first attempt rather than producing a zero delay")
    void guardsAgainstAttemptZero() {
        DeliveryBackoffCalculator calculator = calculator(0.0d, Duration.ofHours(1));

        assertThat(calculator.nextDelay(0)).isEqualTo(Duration.ofSeconds(10));
    }

    private static DeliveryBackoffCalculator calculator(double jitter, Duration maxInterval) {
        NotificationProperties.Retry retry = new NotificationProperties.Retry();
        retry.setInitialInterval(Duration.ofSeconds(10));
        retry.setMultiplier(2.0d);
        retry.setMaxInterval(maxInterval);
        retry.setJitter(jitter);
        return new DeliveryBackoffCalculator(retry);
    }
}
