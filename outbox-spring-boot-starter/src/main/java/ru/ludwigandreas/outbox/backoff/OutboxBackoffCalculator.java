package ru.ludwigandreas.outbox.backoff;

import io.github.resilience4j.core.IntervalFunction;
import ru.ludwigandreas.outbox.config.OutboxProperties;

import java.time.Duration;

/**
 * Stateless exponential-backoff calculator for {@code next_attempt_at}. Deliberately uses
 * resilience4j-core's {@link IntervalFunction} only as a pure interval calculator, not
 * {@code Retry.decorateSupplier}/{@code @Retry}: those retry in-call, which doesn't fit persisting
 * {@code next_attempt_at} and resuming on the next scheduled poll.
 */
public class OutboxBackoffCalculator {

    private final IntervalFunction intervalFunction;

    public OutboxBackoffCalculator(OutboxProperties.Retry retry) {
        this.intervalFunction = IntervalFunction.ofExponentialBackoff(
                retry.getInitialInterval().toMillis(), retry.getMultiplier(), retry.getMaxInterval().toMillis());
    }

    /** @param attempt 1-based total attempt count after the failure that just occurred */
    public Duration nextDelay(int attempt) {
        return Duration.ofMillis(intervalFunction.apply(attempt));
    }
}
