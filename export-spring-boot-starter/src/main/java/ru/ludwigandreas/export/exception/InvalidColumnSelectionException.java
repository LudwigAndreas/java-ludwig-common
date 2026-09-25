package ru.ludwigandreas.export.exception;

import java.util.List;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The request named a column that does not exist, that the requester may not see, or that the
 * source cannot order by.
 *
 * <p>Three situations share one exception because they share one shape - a column id and the set
 * that would have been acceptable - and differ only in the code, which is what a client branches
 * on. Splitting them into three classes would produce three identical bodies.
 *
 * <p>Note what this exception makes impossible: silently dropping a column the requester asked for
 * and may not see. A narrower file than was requested, delivered without comment, is how a person
 * comes to believe a column is empty in the data.
 */
@Getter
public class InvalidColumnSelectionException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String columnId;

    /**
     * Reports an unusable column.
     *
     * @param code      {@link ExportProblemCodes#UNKNOWN_COLUMN},
     *                  {@link ExportProblemCodes#COLUMN_FORBIDDEN} or
     *                  {@link ExportProblemCodes#UNSORTABLE_COLUMN}
     * @param status    the outcome; forbidden for a visibility failure, invalid for the other two
     * @param columnId  the column that was named
     * @param available the column ids that would have been accepted, published as {@code available}
     */
    public InvalidColumnSelectionException(String code, ProblemStatus status, String columnId,
                                           List<String> available) {
        super(status, code, columnId, String.join(", ", available));
        this.columnId = columnId;
        withProperty("column", columnId);
        withProperty("available", List.copyOf(available));
    }
}
