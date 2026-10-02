package ru.ludwigandreas.fileaction.api;

import java.util.List;

/**
 * What a {@link RowHandler} did with one row.
 *
 * <h2>Returned, not thrown</h2>
 *
 * <p>For the reason {@code file-ingest}'s {@code ParsedRecord} gives: whether a rejected row ends the
 * submission is the action's configured commit policy, which is a policy, and a handler that throws has
 * decided it for the module. Throwing is still right for a failure that is not about this row - the
 * database is gone, a partner is unreachable - because there is no row to reject and no way to continue.
 *
 * @param disposition what happened; see {@link Disposition} for why {@code SKIPPED} is not a kind of
 *                    {@code REJECTED}
 * @param code        a stable, non-localised message key when the row was not applied, resolved against
 *                    the module's bundle for display. Null when it was applied
 * @param args        arguments for the message, in order
 */
public record RowOutcome(Disposition disposition, String code, List<String> args) {

    /**
     * The three things that can happen to a row, and the reason the last two are not one.
     *
     * <p>A {@code SKIPPED} row does not count toward the action's {@code reject-threshold}. That
     * threshold exists to catch "this is the wrong file entirely", and a file whose last two hundred
     * rows are duplicates the handler deliberately ignored is not the wrong file. Collapsing the two
     * would make a correct submission fail once it contained enough intentional duplicates - a failure
     * that appears with data volume rather than in any test.
     */
    public enum Disposition {

        /** The row changed something. */
        APPLIED,

        /** The row could not be applied, and the user needs to fix it. Counts toward the threshold. */
        REJECTED,

        /** The handler deliberately ignored the row. Does not count toward the threshold. */
        SKIPPED
    }

    /** Rejects an unapplied row that carries no reason. */
    public RowOutcome {
        if (disposition == null) {
            throw new IllegalArgumentException("A RowOutcome needs a disposition");
        }
        args = args == null ? List.of() : List.copyOf(args);
        if (disposition != Disposition.APPLIED && (code == null || code.isBlank())) {
            throw new IllegalArgumentException(
                    "A " + disposition + " row needs a reason code; without one the reject report says"
                            + " only that something was wrong, which is the report users complain about");
        }
    }

    private static final RowOutcome APPLIED_INSTANCE =
            new RowOutcome(Disposition.APPLIED, null, List.of());

    /** The row was applied. */
    public static RowOutcome applied() {
        return APPLIED_INSTANCE;
    }

    /**
     * The row was not applied, for a reason the user can act on.
     *
     * @param code the message key, which must exist in the action's bundle in every locale
     * @param args the message arguments
     * @return the outcome
     */
    public static RowOutcome rejected(String code, String... args) {
        return new RowOutcome(Disposition.REJECTED, code, List.of(args));
    }

    /**
     * The row was deliberately ignored and is neither applied nor a problem - a duplicate of an earlier
     * row in the same file, say.
     *
     * @param code the message key explaining why
     * @param args the message arguments
     * @return the outcome
     */
    public static RowOutcome skipped(String code, String... args) {
        return new RowOutcome(Disposition.SKIPPED, code, List.of(args));
    }

    /** Whether the row changed something. */
    public boolean isApplied() {
        return disposition == Disposition.APPLIED;
    }

    /** Whether the row counts toward the action's reject threshold. */
    public boolean countsAsReject() {
        return disposition == Disposition.REJECTED;
    }
}
