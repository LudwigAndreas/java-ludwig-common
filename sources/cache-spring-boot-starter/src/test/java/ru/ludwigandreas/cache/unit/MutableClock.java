package ru.ludwigandreas.cache.unit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock a test can move.
 *
 * <p>Every expiry in this module is computed from an entry's write time against an injected clock, which is
 * what makes "the entry is 80% through its TTL" assertable without a {@code Thread.sleep}. A test that slept
 * would be slow and, worse, flaky on a loaded build agent - and the behaviours worth testing here are
 * precisely the ones that happen at a particular point in a TTL.
 */
class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
