package ru.ludwigandreas.job.core.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;

/**
 * When a self-scheduling job runs: either on a fixed delay after the previous run finished, or on a
 * cron expression.
 *
 * <p>Fixed delay rather than fixed rate throughout this platform, and the distinction is not
 * cosmetic. A fixed <em>rate</em> keeps firing while a run is still going, so a job that starts
 * taking longer than its interval silently accumulates overlapping runs until the pool is
 * exhausted - and it does so precisely when the system is already under stress. A fixed
 * <em>delay</em> degrades by running less often, which is visible in a metric and harmless.
 *
 * @param fixedDelay   delay between the end of one run and the start of the next; mutually exclusive
 *                     with {@code cron}
 * @param cron         Spring cron expression (six fields, seconds first); mutually exclusive with
 *                     {@code fixedDelay}
 * @param initialDelay how long after startup the first run happens. Defaulting this to zero would
 *                     have every job in a freshly started pod fire at once, together with the
 *                     connection-pool warm-up and the first readiness probe
 */
public record ScheduleSpec(Duration fixedDelay, String cron, Duration initialDelay) {

    /** Rejects a spec that names both triggers or neither, and an unparseable cron expression. */
    public ScheduleSpec {
        boolean hasCron = cron != null && !cron.isBlank();
        if (hasCron == (fixedDelay != null)) {
            throw new IllegalArgumentException(
                    "Exactly one of fixed-delay or cron must be set (fixedDelay=" + fixedDelay
                            + ", cron=" + cron + ")");
        }
        if (fixedDelay != null && (fixedDelay.isZero() || fixedDelay.isNegative())) {
            throw new IllegalArgumentException("fixed-delay must be positive, was " + fixedDelay);
        }
        if (hasCron && !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("cron is not a valid Spring cron expression: '" + cron + "'");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initial-delay must not be negative, was " + initialDelay);
        }
    }

    /** A fixed-delay schedule whose first run happens one whole delay after startup. */
    public static ScheduleSpec fixedDelay(Duration delay) {
        return new ScheduleSpec(delay, null, delay);
    }

    /** A fixed-delay schedule with an explicit first-run delay. */
    public static ScheduleSpec fixedDelay(Duration delay, Duration initialDelay) {
        return new ScheduleSpec(delay, null, initialDelay);
    }

    /** A cron schedule; {@code initialDelay} is unused, because the expression already says when to fire. */
    public static ScheduleSpec cron(String expression) {
        return new ScheduleSpec(null, expression, Duration.ZERO);
    }

    /** Whether this spec fires on a cron expression rather than a fixed delay. */
    public boolean isCron() {
        return cron != null && !cron.isBlank();
    }
}
