package ru.ludwigandreas.fileaction.api;

/**
 * How much of the lifecycle one submit request performs.
 *
 * <p>The difference between these three is a product decision, not a technical one, which is why it is
 * configuration rather than something the module chooses.
 */
public enum ActionMode {

    /**
     * Read, validate and apply in one request. Nothing is shown to the user first.
     *
     * <p>For a machine caller, and for an action whose effect is genuinely reversible. For anything that
     * creates business records from a spreadsheet a person dragged in, this is the mode that turns one bad
     * file into four hundred wrong orders with no undo - which is why it is not the default.
     */
    DIRECT,

    /**
     * Read and validate, show the user what would happen, and apply only when they confirm.
     *
     * <p>The default. Plan-then-apply, the same shape infrastructure tooling settled on for the same reason:
     * the cost of a wrong apply is borne by whoever has to unpick it, and the preview is the only point at
     * which it is cheap to stop.
     */
    CONFIRM,

    /**
     * Read and validate, and never apply.
     *
     * <p>For a user interface that wants to tell somebody their file is wrong while they are still looking at
     * it. A confirm of a submission belonging to a {@code VALIDATE_ONLY} action is refused rather than
     * silently applied.
     */
    VALIDATE_ONLY;

    /** Whether a separate confirmation is required before anything is applied. */
    public boolean requiresConfirmation() {
        return this == CONFIRM;
    }

    /** Whether this mode ever applies anything. */
    public boolean applies() {
        return this != VALIDATE_ONLY;
    }
}
