package ru.ludwigandreas.fileaction.engine;

import java.nio.file.Path;

/**
 * What the read-and-bind pass produced.
 *
 * @param boundRows  the local newline-delimited artifact holding the rows that bound cleanly
 * @param rowsRead   how many data rows were read, which is the denominator of the reject threshold
 * @param rowsBound  how many bound cleanly and are in the artifact
 * @param rejects    the bounded reject sample and the counts
 * @param sheet      the sheet that was read, or null for a CSV
 */
public record BindOutcome(Path boundRows, long rowsRead, long rowsBound, RejectCollector rejects,
                          String sheet) {

    /** Whether the proportion of refused rows exceeds the action's threshold. */
    public boolean exceedsThreshold(ResolvedAction<?> action) {
        return action.exceedsRejectThreshold(rejects.rejectedRows(), rowsRead);
    }

    /** Whether there is anything to apply. */
    public boolean hasRows() {
        return rowsBound > 0;
    }
}
