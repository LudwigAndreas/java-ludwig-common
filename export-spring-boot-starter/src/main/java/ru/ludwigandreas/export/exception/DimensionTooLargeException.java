package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A dimension stage's catalogue is bigger than the stage declared it could be.
 *
 * <p>The run fails rather than the catalogue being truncated, and that is the whole point of the
 * limit. A truncated dimension produces a file in which an arbitrary subset of rows is marked not
 * found - a file that opens, looks complete, and is wrong in a way the recipient has no way to
 * detect. Failing loudly turns a silent data defect into a configuration message naming the stage
 * and both numbers.
 *
 * <p>What it prevents operationally is subtler: a "reference table" that has quietly grown to a
 * million rows, held in the heap for the life of every run, presenting as an out-of-memory error
 * with no attribution to the stage that caused it.
 */
@Getter
public class DimensionTooLargeException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String stageName;
    private final int limit;
    private final int observed;

    /**
     * Reports an oversized catalogue.
     *
     * @param stageName the stage, published as {@code stage}
     * @param limit     what it declared, published as {@code limit}
     * @param observed  what the partner returned, published as {@code observed}
     */
    public DimensionTooLargeException(String stageName, int limit, int observed) {
        super(ProblemStatus.UNPROCESSABLE, ExportProblemCodes.ENRICHMENT_FAILED, stageName);
        this.stageName = stageName;
        this.limit = limit;
        this.observed = observed;
        withProperty("stage", stageName);
        withProperty("limit", limit);
        withProperty("observed", observed);
    }
}
