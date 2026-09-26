package ru.ludwigandreas.idempotency.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the tests move by hand.
 *
 * <p>The TTL and the lease are both windows measured in hours and minutes, and both have boundary
 * behaviour that has to be pinned: a key one second inside its window must dedup and one second past it
 * must not, and a holder that stopped renewing must lose its claim. Sleeping through those windows is not an
 * option - the suite would take a day - and shortening them for the test would test a different
 * configuration from the one that ships.
 *
 * <p>This works because every decision in the module is made against an injected {@code Clock} and the
 * instant is then passed into the statement as a parameter, rather than the statement calling the database's
 * {@code now()}. That is a deliberate property of the design and this class is the reason it is worth
 * having: a mechanism whose expiry cannot be tested at its boundary is a mechanism whose expiry is assumed.
 */
final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** A clock stopped at a fixed, round instant, so failures read the same on every run. */
    static MutableClock frozen() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    /** Moves the clock forward. */
    void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    /** Puts the clock back where it started, so one case cannot decide the next. */
    void reset() {
        instant = Instant.parse("2026-01-01T00:00:00Z");
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new MutableClock(instant, otherZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
