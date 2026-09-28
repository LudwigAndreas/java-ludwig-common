package ru.ludwigandreas.reconciliation.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/**
 * How recent an external record is, as the owning system describes it: an opaque version token, a
 * timestamp, or neither.
 *
 * <p>This is what makes stale-write protection possible. Responses from a partner arrive out of
 * order - a retried call that was slow, a batch that overtook a per-item fetch, two instances
 * fetching the same key in the same second - and applying the older one last silently regresses the
 * local record's status. That bug produces no error, no log line and no failed metric; the only
 * defence is comparing what arrived against what the local record already reflects, which requires
 * the partner to have told us something comparable.
 *
 * <p>A stamp of {@link #none()} means the partner offers no ordering information. That is a legitimate
 * configuration, and the engine says so explicitly rather than pretending: with no stamp, ordering
 * falls back to fetch order and the task's own reconciler is the only thing standing between an
 * out-of-order response and a regression.
 *
 * @param version   the partner's version token, if it publishes one; compared as a string only for
 *                  equality, never for ordering, because version token formats are not ordered
 * @param timestamp when the partner says the record last changed, if it says
 */
public record ExternalStamp(String version, Instant timestamp) {

    private static final ExternalStamp NONE = new ExternalStamp(null, null);

    /** A stamp carrying no ordering information at all. */
    public static ExternalStamp none() {
        return NONE;
    }

    /** A stamp carrying only the partner's version token. */
    public static ExternalStamp ofVersion(String version) {
        return new ExternalStamp(version, null);
    }

    /** A stamp carrying only the partner's change timestamp. */
    public static ExternalStamp ofTimestamp(Instant timestamp) {
        return new ExternalStamp(null, timestamp);
    }

    /** The version token, if any. */
    public Optional<String> versionToken() {
        return Optional.ofNullable(version);
    }

    /** The change timestamp, if any. */
    public Optional<Instant> changedAt() {
        return Optional.ofNullable(timestamp);
    }

    /** Whether this stamp says anything at all about recency. */
    public boolean isEmpty() {
        return version == null && timestamp == null;
    }

    /**
     * Whether this stamp describes a record strictly older than {@code other}.
     *
     * <p>Only the timestamp can answer this. Version tokens are compared for equality elsewhere - an
     * identical token means "the same record", which is useful - but never for ordering, because
     * nothing guarantees a partner's tokens sort in the order it issued them, and a lexicographic
     * comparison of {@code "10"} and {@code "9"} gets it backwards.
     *
     * @param other the stamp to compare against, typically the newest one already applied locally
     * @return {@code true} only when both stamps carry timestamps and this one is earlier
     */
    public boolean isOlderThan(ExternalStamp other) {
        if (other == null || timestamp == null || other.timestamp == null) {
            return false;
        }
        return timestamp.isBefore(other.timestamp);
    }

    /** Orders stamps by timestamp, treating an absent timestamp as the oldest possible. */
    public static Comparator<ExternalStamp> byTimestamp() {
        return Comparator.comparing(stamp -> stamp.timestamp == null ? Instant.MIN : stamp.timestamp);
    }
}
