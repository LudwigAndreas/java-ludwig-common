package ru.ludwigandreas.fileaction.audit;

/**
 * What a {@link FileActionAuditEvent} can say happened.
 *
 * <p>Constants rather than an enum, because {@code AuditEvent.action} is a string across the whole platform and
 * an enum here would be converted to one at every call site - which is a conversion that can disagree with
 * itself. The {@code audit-core} convention is the same: {@code AuditActions} is constants.
 */
public final class FileActionAuditActions {

    /** A file was accepted, stored and claimed. */
    public static final String SUBMITTED = "file-action.submitted";

    /** A file was refused before anything was applied. */
    public static final String REJECTED = "file-action.rejected";

    /** A scanner answered about a file. Recorded whether it was safe or not. */
    public static final String SCANNED = "file-action.scanned";

    /** A file was read and bound, and the result is waiting for a confirmation. */
    public static final String VALIDATED = "file-action.validated";

    /** A person confirmed a validated submission. */
    public static final String CONFIRMED = "file-action.confirmed";

    /** The handler ran. */
    public static final String APPLIED = "file-action.applied";

    /** Somebody asked a running submission to stop. */
    public static final String CANCELLATION_REQUESTED = "file-action.cancellation-requested";

    /** Retention removed a submission's stored bytes. */
    public static final String EXPIRED = "file-action.expired";

    /** A repeated upload of the same content was answered with the first submission's envelope. */
    public static final String DUPLICATE = "file-action.duplicate";

    private FileActionAuditActions() {
    }
}
