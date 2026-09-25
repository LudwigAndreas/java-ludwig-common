package ru.ludwigandreas.ingest.exception;

/**
 * Too large a share of the file could not be processed, so the run is failed.
 *
 * <h2>What the threshold catches that the other two policies do not</h2>
 *
 * <p>{@code fail-fast} dies on row one, which is exactly right for a file that is <em>entirely</em>
 * wrong and useless for a file with one bad row in four million. Quarantining everything and
 * continuing regardless happily imports four million rows of garbage and reports success. The failure
 * neither handles is the middle one, and it is the common one: a partner adds a column, or changes a
 * delimiter, and the file parses - every row produces a record, and every record is wrong. The rate is
 * what makes that visible, because it goes from a handful of rows a day to most of the file.
 *
 * <p>Carries the observed ratio and the limit, so the message says how wrong rather than that
 * something was.
 */
public class QuarantineThresholdExceededException extends IngestException {

    private static final long serialVersionUID = 1L;

    /**
     * A run past its quarantine budget.
     *
     * @param task        the task name
     * @param quarantined how many records were quarantined
     * @param read        how many were read
     * @param ratio       the observed ratio
     * @param limit       the configured limit
     */
    public QuarantineThresholdExceededException(String task, long quarantined, long read,
                                                double ratio, double limit) {
        super("Ingest task '" + task + "' quarantined " + quarantined + " of " + read + " records ("
                + String.format("%.4f", ratio) + "), past its max-quarantine-ratio of " + limit
                + ". The run is FAILED and the target is unchanged. A rate this far above normal is"
                + " usually a changed delimiter or a shifted column rather than bad data.");
    }
}
