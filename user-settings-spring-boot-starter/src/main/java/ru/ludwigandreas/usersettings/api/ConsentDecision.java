package ru.ludwigandreas.usersettings.api;

/**
 * What a consent row records.
 *
 * <p>There is no third constant and there will not be one. "Withdrawn", "expired" and "superseded"
 * are all conclusions drawn from the history - the row that follows, or the absence of one within a
 * validity window - and storing them as their own decision would make the same fact representable
 * two ways, which is how a consent ledger stops agreeing with itself.
 */
public enum ConsentDecision {

    /** The subject agreed to a specific version of the consent text. */
    GRANTED,

    /** The subject withdrew a previous agreement. A new row, never an update of the old one. */
    REVOKED
}
