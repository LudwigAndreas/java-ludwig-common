package ru.ludwigandreas.reconciliation.engine.enumeration;

/**
 * What applying one page - or a whole walk - did, as counts.
 *
 * @param applied   members created or changed
 * @param unchanged members seen with nothing to write, by hash or by the reconciler's own answer
 * @param skipped   items the reconciler declined
 * @param failed    members the reconciler threw on
 * @param held      members not handed over: backing off after a failure, or quarantined
 */
public record PageOutcome(int applied, int unchanged, int skipped, int failed, int held) {

    /** Nothing happened. */
    public static final PageOutcome NONE = new PageOutcome(0, 0, 0, 0, 0);

    /**
     * Adds two outcomes.
     *
     * @param other the outcome to add
     * @return the sum
     */
    public PageOutcome plus(PageOutcome other) {
        return new PageOutcome(applied + other.applied, unchanged + other.unchanged,
                skipped + other.skipped, failed + other.failed, held + other.held);
    }

    /** How many items the page carried. */
    public int total() {
        return applied + unchanged + skipped + failed + held;
    }
}
