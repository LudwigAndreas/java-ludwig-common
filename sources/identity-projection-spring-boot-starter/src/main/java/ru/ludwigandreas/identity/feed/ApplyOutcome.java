package ru.ludwigandreas.identity.feed;

/** What the projection did with one change. */
public enum ApplyOutcome {

    /** Stored. */
    APPLIED("applied"),

    /** Skipped: the stored state happened at the same time or later. Normal under replay and reordering. */
    STALE("stale"),

    /** Refused: the subject belongs to another source. */
    FOREIGN_SOURCE("foreign-source"),

    /** Dropped: the change named no subject. */
    DISCARDED("discarded");

    private final String tag;

    ApplyOutcome(String tag) {
        this.tag = tag;
    }

    /**
     * The outcome as metrics tag it.
     *
     * @return the tag value
     */
    public String tag() {
        return tag;
    }
}
