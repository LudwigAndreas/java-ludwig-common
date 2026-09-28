package ru.ludwigandreas.ingest.engine;

import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.exception.QuarantineThresholdExceededException;

/**
 * Decides whether a run's quarantine count is still acceptable.
 *
 * <h2>Why the default is a rate and not a count</h2>
 *
 * <p>The three available answers to a record that will not process are: fail immediately, continue
 * regardless, and continue while it stays rare. The first two each handle one case well and the other
 * badly, and neither handles the case that actually happens.
 *
 * <ul>
 *   <li><b>Fail fast</b> is right for a file that is entirely wrong - a partner sending last year's
 *       format, or a file that is not the file at all - because dying on row one is the fastest and
 *       clearest possible signal. It is wrong for one bad row in four million, where it costs a whole
 *       day's data for a record nobody needed.</li>
 *   <li><b>Continue regardless</b> is right for a feed known to carry occasional junk. It happily
 *       imports four million rows of garbage and reports success, which is why it is not offered
 *       here at all.</li>
 *   <li><b>A rate</b> catches the case the other two miss and the one that occurs most: a partner
 *       adds a column, or changes a delimiter, and the file <em>parses</em>. Every row produces a
 *       record and every record is wrong. Nothing about that file is malformed enough for fail-fast
 *       to trigger on row one, and nothing about it is rare enough to be junk. The rate goes from a
 *       handful of rows a day to most of the file, and that is the signal.</li>
 * </ul>
 *
 * <p>The ratio's default is deliberately small, for the same reason: what it is measuring is not bad
 * data but a changed format, and a generous ratio would let a shifted column through.
 */
public final class QuarantinePolicyCheck {

    private QuarantinePolicyCheck() {
    }

    /**
     * Checks the run against its task's policy.
     *
     * <p>Called after every batch rather than only at the end, so a file that is wholly wrong fails in
     * the first few seconds instead of after forty minutes of quarantining every row it reads. The
     * ratio is meaningless on a handful of records, so it is not applied until at least one full
     * batch has been read - otherwise a run whose very first record is bad would fail at a ratio of
     * 1.0 under every threshold, which is fail-fast wearing the wrong name.
     *
     * @param task        the task name, for the message
     * @param read        records read so far
     * @param quarantined records quarantined so far
     * @param minimum     how many records must have been read before the ratio is meaningful
     * @param quarantine  the task's quarantine configuration
     * @throws QuarantineThresholdExceededException if the policy says the run should stop
     */
    public static void verify(String task, long read, long quarantined, long minimum,
                              FileIngestProperties.Quarantine quarantine) {
        if (quarantined == 0) {
            return;
        }
        if (quarantine.getPolicy() == FileIngestProperties.QuarantinePolicy.FAIL_FAST) {
            throw new QuarantineThresholdExceededException(task, quarantined, read,
                    ratio(quarantined, read), 0);
        }
        if (read < minimum) {
            return;
        }
        double observed = ratio(quarantined, read);
        if (observed > quarantine.getMaxRatio()) {
            throw new QuarantineThresholdExceededException(task, quarantined, read, observed,
                    quarantine.getMaxRatio());
        }
    }

    private static double ratio(long quarantined, long read) {
        return read == 0 ? 0 : (double) quarantined / read;
    }
}
