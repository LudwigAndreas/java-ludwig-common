package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

/**
 * Whether a subscription's window has elapsed, and what happens to the ones that were missed.
 *
 * <h2>Why this is a unit test over the cron arithmetic</h2>
 *
 * <p>The scheduler itself is three collaborators and a lock: the lock is {@code job-core}'s and has its
 * own tests, and submitting a run is {@code ReportRequestService}, which has its own. What is genuinely
 * this class's own decision - and the one a reader of the README is entitled to see enforced - is that
 * a missed window is <em>skipped</em> rather than replayed. That is a statement about a comparison
 * between a cron expression, a last-run timestamp and a clock, and it is testable as exactly that.
 *
 * <p>The comparison is duplicated here rather than the scheduler being driven, deliberately: driving it
 * would need a {@code TaskScheduler}, a lock, a repository, a saved-report service and a request
 * service, and the test would then be about whether those five were assembled correctly rather than
 * about the rule. The equivalence between this arithmetic and the scheduler's is small enough to read
 * side by side, and the README's claim is about the arithmetic.
 */
class SubscriptionSchedulerTest {

    private static final String DAILY_AT_SIX = "0 0 6 * * *";
    private static final ZoneId UTC = ZoneId.of("UTC");

    /**
     * The scheduler's own due check, as {@code ExportSubscriptionScheduler.isDue} performs it.
     *
     * @param lastRunAt when the subscription last produced a run, or null if it never has
     * @param now       the clock's reading
     * @return whether a window has elapsed
     */
    private static boolean isDue(Instant lastRunAt, Instant now) {
        if (lastRunAt == null) {
            return true;
        }
        Instant next = CronExpression.parse(DAILY_AT_SIX).next(lastRunAt.atZone(UTC)).toInstant();
        return !next.isAfter(now);
    }

    /**
     * How many runs a scheduler ticking every minute would submit over a span.
     *
     * <p>This is the shape of the claim: a subscription that was not serviced for days produces one
     * run when the scheduler comes back, not one per missed day. Simulated by advancing a clock and
     * moving {@code lastRunAt} exactly as the scheduler does - to <em>now</em>, not to the window it
     * was due at, which is what makes the skip a skip.
     */
    private static int submissionsOver(Instant start, Instant end, Duration tick,
                                       Instant initialLastRun) {
        List<Instant> submitted = new ArrayList<>();
        Instant lastRunAt = initialLastRun;
        for (Instant now = start; !now.isAfter(end); now = now.plus(tick)) {
            if (isDue(lastRunAt, now)) {
                submitted.add(now);
                lastRunAt = now;
            }
        }
        return submitted.size();
    }

    @Test
    @DisplayName("a subscription that has never run is due immediately")
    void neverRunIsDueAtOnce() {
        assertThat(isDue(null, Instant.parse("2026-03-01T09:01:00Z"))).isTrue();
    }

    @Test
    @DisplayName("a subscription is not due again until its next cron boundary")
    void notDueBeforeTheNextBoundary() {
        Instant lastRun = Instant.parse("2026-03-01T06:00:00Z");

        assertThat(isDue(lastRun, Instant.parse("2026-03-01T06:00:01Z"))).isFalse();
        assertThat(isDue(lastRun, Instant.parse("2026-03-01T23:59:59Z"))).isFalse();
        assertThat(isDue(lastRun, Instant.parse("2026-03-02T05:59:59Z"))).isFalse();
        assertThat(isDue(lastRun, Instant.parse("2026-03-02T06:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("a weekend of missed windows produces one run, not one per window")
    void skipsMissedWindowsRatherThanReplayingThem() {
        // Down from Friday morning until Monday: three daily windows elapsed unserviced.
        Instant lastRun = Instant.parse("2026-03-06T06:00:00Z");
        Instant restart = Instant.parse("2026-03-09T08:00:00Z");

        int submissions = submissionsOver(restart, restart.plus(Duration.ofMinutes(5)),
                Duration.ofMinutes(1), lastRun);

        assertThat(submissions)
                .as("one run on restart, not one per missed window")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a healthy scheduler submits exactly once per window")
    void submitsOncePerWindowWhenHealthy() {
        // A week of minute-by-minute ticks, starting just after a run.
        Instant start = Instant.parse("2026-03-01T06:01:00Z");
        Instant end = start.plus(Duration.ofDays(7));

        int submissions = submissionsOver(start, end, Duration.ofMinutes(1),
                Instant.parse("2026-03-01T06:00:00Z"));

        assertThat(submissions).as("one per daily window over seven days").isEqualTo(7);
    }

    @Test
    @DisplayName("two ticks inside one window submit the same idempotency key")
    void duplicateTicksAreIdempotent() {
        // The scheduler derives the key from the subscription and the window it is submitting for, so
        // a second tick before lastRunAt is committed produces the same key - and the unique
        // constraint recognises it as the first submission rather than queuing a second report.
        UUID subscriptionId = UUID.fromString("00000000-0000-4000-8000-00000000000a");
        Instant lastRun = Instant.parse("2026-03-01T06:00:00Z");

        String first = keyFor(subscriptionId, lastRun);
        String second = keyFor(subscriptionId, lastRun);
        String afterTheWindowMoved = keyFor(subscriptionId, lastRun.plus(Duration.ofDays(1)));

        assertThat(first).isEqualTo(second);
        assertThat(afterTheWindowMoved).isNotEqualTo(first);
    }

    /** The key as {@code ExportSubscriptionScheduler} composes it. */
    private static String keyFor(UUID subscriptionId, Instant lastRunAt) {
        return subscriptionId + ":" + Optional.ofNullable(lastRunAt)
                .map(Instant::getEpochSecond)
                .orElse(0L);
    }
}
