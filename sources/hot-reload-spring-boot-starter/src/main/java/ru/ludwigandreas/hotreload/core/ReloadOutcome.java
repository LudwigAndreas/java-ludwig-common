package ru.ludwigandreas.hotreload.core;

/** What one reload of a source did on this replica. */
public enum ReloadOutcome {

    /** The content changed (or loaded for the first time) and listeners were notified. */
    RELOADED,

    /** The content was read and is identical to the last-known-good content; nothing was notified. */
    UNCHANGED,

    /**
     * The content could not be read or parsed, or a typed configuration refused it; the last-known-good value is
     * kept.
     */
    REJECTED
}
