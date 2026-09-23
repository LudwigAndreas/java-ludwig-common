package ru.ludwigandreas.job.core.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.job.core.backoff.BackoffPolicy;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BackoffCalculatorTest {

    private static final BackoffPolicy DETERMINISTIC =
            BackoffPolicy.of(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(10));

    @Test
    void firstAttemptWaitsTheInitialInterval() {
        BackoffCalculator calculator = new BackoffCalculator(DETERMINISTIC);

        assertThat(calculator.nextDelay(1)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void intervalGrowsByTheMultiplierUntilItReachesTheCap() {
        BackoffCalculator calculator = new BackoffCalculator(DETERMINISTIC);

        assertThat(calculator.nextDelay(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(calculator.nextDelay(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(calculator.nextDelay(4)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void intervalIsClampedAtMaxIntervalAndNeverGrowsPastIt() {
        BackoffCalculator calculator = new BackoffCalculator(DETERMINISTIC);

        assertThat(calculator.nextDelay(20)).isEqualTo(Duration.ofMinutes(10));
        assertThat(calculator.nextDelay(100)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void multiplierOfOneProducesAFixedInterval() {
        BackoffCalculator calculator = new BackoffCalculator(
                BackoffPolicy.of(Duration.ofSeconds(30), 1.0, Duration.ofMinutes(10)));

        assertThat(calculator.nextDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(calculator.nextDelay(9)).isEqualTo(Duration.ofSeconds(30));
    }

    /**
     * The point of jitter is that two records failing in the same batch do not come due at the same
     * instant, so the assertion is that repeated calls for the same attempt differ - not that any
     * particular value comes out.
     */
    @Test
    void jitterSpreadsTheSameAttemptOverAWindow() {
        BackoffCalculator calculator = new BackoffCalculator(
                new BackoffPolicy(Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10), 0.5));

        long distinct = IntStream.range(0, 50)
                .mapToObj(i -> calculator.nextDelay(3))
                .distinct()
                .count();

        assertThat(distinct).isGreaterThan(1);
    }

    @Test
    void jitteredIntervalsStayWithinTheRandomizationWindow() {
        double jitter = 0.3;
        BackoffCalculator calculator = new BackoffCalculator(
                new BackoffPolicy(Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10), jitter));

        // Attempt 2 is a nominal 20s; the window is [14s, 26s].
        IntStream.range(0, 200).forEach(i -> assertThat(calculator.nextDelay(2))
                .isBetween(Duration.ofSeconds(14), Duration.ofSeconds(26)));
    }

    @Test
    void rejectsAMultiplierBelowOneBecauseItWouldRetryFasterTheWorseThingsGet() {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> new BackoffPolicy(Duration.ofSeconds(1), 0.5, Duration.ofMinutes(1), 0.0))
                .withMessageContaining("multiplier");
    }

    @Test
    void rejectsAMaxIntervalBelowTheInitialInterval() {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> new BackoffPolicy(Duration.ofMinutes(5), 2.0, Duration.ofSeconds(1), 0.0))
                .withMessageContaining("maxInterval");
    }

    @Test
    void rejectsJitterOutsideTheUnitInterval() {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> new BackoffPolicy(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 1.5))
                .withMessageContaining("jitter");
    }

    @Test
    void rejectsANonPositiveInitialInterval() {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> new BackoffPolicy(Duration.ZERO, 2.0, Duration.ofMinutes(1), 0.0))
                .withMessageContaining("initialInterval");
    }
}
