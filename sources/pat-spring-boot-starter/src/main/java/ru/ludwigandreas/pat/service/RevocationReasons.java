package ru.ludwigandreas.pat.service;

/**
 * Why a token was revoked, as a closed set of names.
 *
 * <p>A closed set rather than free text, because the reasons lead to different actions and an auditor
 * reading a list of revoked tokens needs to tell them apart without parsing prose. A departure, an incident
 * response and a housekeeping sweep look identical in a {@code revoked_at} column alone.
 *
 * <p>Constants rather than an enum, because the column is a {@code varchar} that will outlive this code:
 * a reason written by an older release must still be readable, and an enum turns an unrecognised stored
 * value into a deserialization failure rather than a string an operator can still read.
 */
public final class RevocationReasons {

    /** An operator or the owner asked for it. The common case. */
    public static final String REQUESTED = "requested";

    /** The identity projection reported the owner disabled or removed. */
    public static final String OWNER_DISABLED = "owner-disabled";

    /** Unused for longer than {@code ludwig.pat.inactivity-expiry}. */
    public static final String INACTIVITY = "inactivity";

    /** Revoked as part of responding to a suspected compromise. */
    public static final String COMPROMISED = "compromised";

    private RevocationReasons() {
    }
}
