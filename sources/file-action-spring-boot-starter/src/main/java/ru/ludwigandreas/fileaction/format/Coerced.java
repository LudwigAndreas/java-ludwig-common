package ru.ludwigandreas.fileaction.format;

/**
 * The outcome of coercing one cell: either a value, which may legitimately be {@code null}, or a refusal.
 *
 * <h2>Why this is not an {@code Optional}</h2>
 *
 * <p>It was an {@code Optional<Object>} first, and that is wrong in a way the compiler cannot see: an
 * {@code Optional} cannot hold {@code null}, so "the user left this cell blank and the record accepts
 * null there" and "this cell cannot be read as a number at all" both arrive as {@code Optional.empty()}.
 * The first is a perfectly good row; the second is a reject. Collapsing them turns every blank optional
 * cell into a rejected row, which is the kind of defect that passes review because the code reads
 * correctly.
 *
 * @param coercible whether the cell could be read as the target type at all
 * @param value     the coerced value when it could, which may be {@code null} for a blank cell whose
 *                  target is a reference type. Always {@code null} when {@link #coercible()} is false
 */
public record Coerced(boolean coercible, Object value) {

    private static final Coerced NOT_COERCIBLE = new Coerced(false, null);

    /** Rejects a refusal that carries a value, which would be a contradiction. */
    public Coerced {
        if (!coercible && value != null) {
            throw new IllegalArgumentException(
                    "a cell that could not be coerced has no value, and carrying one would let a caller"
                            + " read it without checking");
        }
    }

    /**
     * A cell that coerced, possibly to null.
     *
     * @param value the value, which may be null
     * @return the outcome
     */
    public static Coerced of(Object value) {
        return new Coerced(true, value);
    }

    /** A blank cell whose target accepts null. */
    public static Coerced ofNothing() {
        return new Coerced(true, null);
    }

    /** A cell that cannot be read as the target type. */
    public static Coerced notCoercible() {
        return NOT_COERCIBLE;
    }
}
