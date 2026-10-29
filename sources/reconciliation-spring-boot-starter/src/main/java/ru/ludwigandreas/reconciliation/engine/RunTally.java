package ru.ludwigandreas.reconciliation.engine;

/**
 * What one run counted as it went, for its history row. Written by the run's own thread only.
 */
public final class RunTally {

    private int demanded;
    private int fetched;
    private int produced;

    void setDemanded(int count) {
        demanded = count;
    }

    void addFetched(int count) {
        fetched += count;
    }

    void setProduced(int count) {
        produced = count;
    }

    /** Correlation keys the demand produced, after suppression. */
    public int demanded() {
        return demanded;
    }

    /** Records the partner returned. */
    public int fetched() {
        return fetched;
    }

    /** Rows staged, or applied under direct mode. */
    public int produced() {
        return produced;
    }
}
