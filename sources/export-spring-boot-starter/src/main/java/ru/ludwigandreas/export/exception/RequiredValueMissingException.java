package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A column whose {@code NullPolicy} is {@code FAIL} had no value in a row.
 *
 * <p>This is the loud end of the null policy, and it exists for columns the definition considers
 * structurally impossible to be absent - a primary key, a status with a database-level default.
 * Reaching it means the query and the definition have drifted apart, and a loud failure is the right
 * answer: the quiet alternatives are a file with holes in it that a reader interprets as data, or a
 * column of zeroes that a reader sums.
 *
 * <p>The row is not identified in the message. A report's rows are the data, and naming one in an
 * error that is logged and returned to a caller would put a production record into places the
 * report's own PII rules were written to keep it out of.
 */
@Getter
public class RequiredValueMissingException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String columnId;
    private final long rowNumber;

    /**
     * Reports a required value that was absent.
     *
     * @param columnId  the column, published as {@code column}
     * @param rowNumber which row of the run, published as {@code row}. A position, not an
     *                  identifier: it is enough to find the record without carrying it
     */
    public RequiredValueMissingException(String columnId, long rowNumber) {
        super(ProblemStatus.UNPROCESSABLE, ExportProblemCodes.REQUIRED_VALUE_MISSING, columnId);
        this.columnId = columnId;
        this.rowNumber = rowNumber;
        withProperty("column", columnId);
        withProperty("row", rowNumber);
    }
}
