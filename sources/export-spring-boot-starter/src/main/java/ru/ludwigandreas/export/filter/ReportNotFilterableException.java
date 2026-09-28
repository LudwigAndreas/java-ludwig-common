package ru.ludwigandreas.export.filter;

import lombok.Getter;
import ru.ludwigandreas.export.exception.ExportProblemCodes;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A request carried a {@code $filter} for a report that declares none.
 *
 * <p>Refused rather than ignored, which is the same decision the module makes about an unknown format
 * option and for a larger reason: a filter that was silently dropped produces a file covering far
 * more data than was asked for. For a report that is not a smaller failure than an error - it is a
 * bulk extract somebody believes is narrow.
 */
@Getter
public class ReportNotFilterableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String definitionKey;

    public ReportNotFilterableException(String definitionKey) {
        super(ProblemStatus.INVALID, ExportProblemCodes.NOT_FILTERABLE, definitionKey);
        this.definitionKey = definitionKey;
        withProperty("definition", definitionKey);
    }
}
