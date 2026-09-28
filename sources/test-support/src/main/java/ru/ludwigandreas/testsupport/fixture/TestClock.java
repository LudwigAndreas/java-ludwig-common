package ru.ludwigandreas.testsupport.fixture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} a test moves by hand.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Several components on this platform already take a {@code Clock} for exactly this reason -
 * {@code ExportRetentionPurge} among them - so the seam is there and correct. What was missing was one
 * implementation to put in it, so each test improvised: a fixed {@code Clock.fixed(...)} that cannot
 * advance, an anonymous subclass, or {@code Clock.systemUTC()} plus a {@code Thread.sleep}. The last is
 * the one worth eliminating: a test that sleeps is slow when it passes and flaky when it fails, which is
 * the worst combination for the retention, leasing and backoff properties these clocks exist to make
 * testable.
 *
 * <p>With this class {@code Clock.systemUTC()} need never appear in a test again.
 *
 * <h2>Why mutable rather than a new instance per instant</h2>
 *
 * <p>The component under test is handed the clock once, at construction or injection. A test that
 * wanted to move time by replacing the clock would have to rebuild the component, which discards the
 * state the assertion is usually about. So the instant is mutable and the reference is stable.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The instant is {@code volatile}, so a background poller reading the clock while the test advances
 * it sees the new value rather than a stale cached one. The concurrency tests in this repository run N
 * threads against one component, and a clock that was not safe to read from them would be a source of
 * flakes rather than a cure for them.
 */
public final class TestClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /**
     * A clock at a given instant, in UTC.
     *
     * <p>UTC and not the system default deliberately: a test that passes in one timezone and fails in
     * another is a test about the developer's laptop.
     *
     * @param instant where time starts
     * @return a movable clock
     */
    public static TestClock at(Instant instant) {
        return new TestClock(instant, ZoneOffset.UTC);
    }

    /**
     * A clock at a given instant, in a given zone.
     *
     * <p>For the case where the zone is the thing under test - a quiet-hours window, or a daily digest
     * that has to fire at a local time.
     *
     * @param instant where time starts
     * @param zone    the zone the clock reports
     * @return a movable clock
     */
    public static TestClock at(Instant instant, ZoneId zone) {
        return new TestClock(instant, zone);
    }

    /**
     * A clock at a fixed, arbitrary-but-stable instant, in UTC.
     *
     * <p>For a test that needs a clock to exist but does not care what it reads. The instant is a round
     * number in the past so that a value leaking into an assertion message is recognisably a fixture
     * rather than a real timestamp.
     *
     * @return a movable clock
     */
    public static TestClock fixed() {
        return at(Instant.parse("2024-01-01T00:00:00Z"));
    }

    /**
     * Moves the clock forward.
     *
     * @param amount how far; may be negative, for the tests that check a clock going backwards
     * @return this clock, so a test can advance and read in one expression
     */
    public TestClock advance(Duration amount) {
        this.instant = this.instant.plus(amount);
        return this;
    }

    /**
     * Moves the clock to an absolute instant.
     *
     * @param newInstant the instant to report from now on
     * @return this clock
     */
    public TestClock set(Instant newInstant) {
        this.instant = newInstant;
        return this;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
