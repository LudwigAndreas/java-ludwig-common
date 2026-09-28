package ru.ludwigandreas.ingest.engine;

/**
 * What one committed batch accounted for.
 *
 * @param applied     rows written into staging
 * @param quarantined records set aside during mapping
 * @param skipped     records the applier deliberately dropped
 */
public record BatchOutcome(int applied, int quarantined, int skipped) {

    /** A batch that accounted for nothing. */
    public static final BatchOutcome EMPTY = new BatchOutcome(0, 0, 0);

    /**
     * How many records this batch accounted for in total.
     *
     * <p>The number the balance check adds up. Having it here rather than summing at each call site
     * is what keeps the three components from being added in two different combinations in two
     * different places.
     *
     * @return applied plus quarantined plus skipped
     */
    public int accounted() {
        return applied + quarantined + skipped;
    }
}
