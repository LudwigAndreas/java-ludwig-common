package ru.ludwigandreas.notification.service.queue;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * How long a delivery waits before its next attempt.
 *
 * <p>Built on resilience4j's {@link IntervalFunction} as a pure interval calculator, exactly as the
 * outbox module's {@code OutboxBackoffCalculator} does, and for the same reason: resilience4j's
 * {@code Retry} decorator retries <em>in call</em>, blocking a thread, which is the opposite of what
 * a persisted work queue wants. Here the delay is written to {@code next_attempt_at} and the thread
 * goes back to claiming other work.
 *
 * <h2>Jitter</h2>
 *
 * <p>Randomised over a band around the computed delay, which the outbox's calculator does not do and
 * which this queue needs. An SMTP relay going down fails every claimed delivery within a second or
 * two of each other; without jitter they all get the same delay and therefore the same
 * {@code next_attempt_at}, so the recovering relay is hit by the entire backlog in one synchronised
 * burst - which fails them all again, together, and the convoy never disperses. Spreading the
 * retries is what turns a thundering herd back into a queue.
 *
 * <p>Stateless and therefore safe to share; the randomness comes from {@link ThreadLocalRandom}
 * rather than a shared {@code Random}, which would be a contention point on the dispatch path.
 */
public class DeliveryBackoffCalculator {

    private final IntervalFunction intervalFunction;
    private final double jitter;

    public DeliveryBackoffCalculator(NotificationProperties.Retry retry) {
        this.intervalFunction = IntervalFunction.ofExponentialBackoff(
                retry.getInitialInterval().toMillis(),
                retry.getMultiplier(),
                retry.getMaxInterval().toMillis());
        this.jitter = Math.max(0.0d, retry.getJitter());
    }

    /**
     * @param attempt 1-based total attempt count after the failure that just occurred
     * @return the delay before the next attempt, never negative
     */
    public Duration nextDelay(int attempt) {
        long base = intervalFunction.apply(Math.max(1, attempt));
        if (jitter <= 0.0d) {
            return Duration.ofMillis(base);
        }
        long spread = (long) (base * jitter);
        if (spread <= 0L) {
            return Duration.ofMillis(base);
        }
        // Symmetric around the base rather than additive, so the average backoff still matches the
        // configured curve - an additive jitter would silently make every delay longer than
        // configured, and the effect compounds over eight attempts.
        long offset = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
        return Duration.ofMillis(Math.max(0L, base + offset));
    }
}
