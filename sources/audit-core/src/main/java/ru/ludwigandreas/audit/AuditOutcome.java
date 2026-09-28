package ru.ludwigandreas.audit;

/**
 * How the thing an audit event describes turned out.
 *
 * <p>Four values rather than a boolean. {@link #DENIED} and {@link #FAILURE} are the distinction an
 * incident responder needs first - "we refused them" and "we broke" look identical in a
 * {@code granted=false} column and lead to opposite investigations - and {@link #PARTIAL} exists
 * because this platform has real operations that half-succeed and are recorded as successes today:
 * an export run whose enrichment stage degraded, an ingest pass that quarantined some records and
 * merged the rest.
 *
 * @param status what happened
 * @param reason why, in a form a person reads: a denial reason, an error message, which stages
 *               degraded. Never a payload and never a credential
 */
public record AuditOutcome(Status status, String reason) {

    /** The possible outcomes. */
    public enum Status {

        /** It did what was asked. */
        SUCCESS,

        /** It was attempted and broke. */
        FAILURE,

        /** It was refused on purpose - an authorization decision, a quota, a policy. */
        DENIED,

        /** It did some of what was asked, and the rest is described by {@link AuditOutcome#reason()}. */
        PARTIAL
    }

    /** A plain success. */
    public static AuditOutcome success() {
        return new AuditOutcome(Status.SUCCESS, null);
    }

    /** A failure, with the reason it failed. */
    public static AuditOutcome failure(String reason) {
        return new AuditOutcome(Status.FAILURE, reason);
    }

    /** A deliberate refusal, with the reason it was refused. */
    public static AuditOutcome denied(String reason) {
        return new AuditOutcome(Status.DENIED, reason);
    }

    /** A partial success, with what was left undone. */
    public static AuditOutcome partial(String reason) {
        return new AuditOutcome(Status.PARTIAL, reason);
    }

    /** Whether this outcome describes a state change that actually took effect. */
    public boolean isEffective() {
        return status == Status.SUCCESS || status == Status.PARTIAL;
    }
}
