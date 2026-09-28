package ru.ludwigandreas.export.api;

/**
 * What an enrichment stage does when a partner answers successfully and simply does not know a key.
 *
 * <p>The distinction between this and {@link FailurePolicy} is the one this module refuses to blur.
 * A partner that returns two hundred records for two hundred and five keys has not failed - it has
 * told the engine something true about five of them - and treating that as an error is what turns a
 * normal report into a retry loop against a partner that will keep giving the same answer. The
 * corresponding note is written on {@link Enricher.Batched#fetchBatch}, where an implementation
 * could otherwise be tempted to throw.
 *
 * <p>A policy is a value rather than an enum constant because the common case carries a message key:
 * the placeholder text is localized like everything else a reader sees.
 *
 * @param kind       what to do
 * @param messageKey the bundle key for {@link Kind#PLACEHOLDER}; null for the other kinds
 */
public record MissingPolicy(Kind kind, String messageKey) {

    /** The module's own placeholder text, used when a stage asks for a placeholder without naming one. */
    public static final String DEFAULT_PLACEHOLDER_KEY = "ludwig.export.cell.not-found";

    /** The three answers to "the partner does not know this key". */
    public enum Kind {

        /** Write a localized marker in the enriched cells and count it. */
        PLACEHOLDER,

        /** Drop the row from the report and count it. The row is not written to any sheet. */
        FAIL_ROW,

        /** Fail the whole run, naming the stage and the key. */
        FAIL_REPORT
    }

    public MissingPolicy {
        if (kind == null) {
            throw new IllegalArgumentException("A MissingPolicy needs a kind");
        }
        if (kind == Kind.PLACEHOLDER && (messageKey == null || messageKey.isBlank())) {
            throw new IllegalArgumentException("MissingPolicy.PLACEHOLDER needs a message key");
        }
        if (kind != Kind.PLACEHOLDER && messageKey != null) {
            throw new IllegalArgumentException(
                    "MissingPolicy message key is only meaningful for PLACEHOLDER, was set on: " + kind);
        }
    }

    /**
     * The default: a localized marker in the cell.
     *
     * <p>Chosen as the default because it is the only one of the three that reports what actually
     * happened. Dropping the row hides it, and failing the run escalates a routine gap - a customer
     * deleted in a partner system last week - into an outage of the reporting function.
     */
    public static MissingPolicy placeholder() {
        return new MissingPolicy(Kind.PLACEHOLDER, DEFAULT_PLACEHOLDER_KEY);
    }

    /** A localized marker with wording of the definition's own choosing. */
    public static MissingPolicy placeholder(String messageKey) {
        return new MissingPolicy(Kind.PLACEHOLDER, messageKey);
    }

    /** Omit the row entirely. For a report whose rows are meaningless without this stage. */
    public static MissingPolicy failRow() {
        return new MissingPolicy(Kind.FAIL_ROW, null);
    }

    /** Fail the run. For a stage whose keys are guaranteed by a foreign key somewhere. */
    public static MissingPolicy failReport() {
        return new MissingPolicy(Kind.FAIL_REPORT, null);
    }
}
