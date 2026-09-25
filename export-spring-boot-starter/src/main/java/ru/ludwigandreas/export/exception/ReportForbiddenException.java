package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The requester holds none of the authorities the definition requires to run it at all.
 *
 * <p>Distinct from {@link InvalidColumnSelectionException} with a {@code COLUMN_FORBIDDEN} code,
 * which is about one column of a report they may otherwise run. The distinction matters to whoever
 * reads the audit trail: one is somebody reaching for a report that is not theirs, the other is
 * somebody running their own report and asking for a column they are not entitled to.
 */
@Getter
public class ReportForbiddenException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String definitionKey;

    public ReportForbiddenException(String definitionKey) {
        super(ProblemStatus.FORBIDDEN, ExportProblemCodes.REPORT_FORBIDDEN, definitionKey);
        this.definitionKey = definitionKey;
        withProperty("definition", definitionKey);
    }
}
