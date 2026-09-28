package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A run hit the row cap or the wall-clock budget and was stopped.
 *
 * <p>Both numbers are published, the limit and what was actually observed, because the only useful
 * response to this is to narrow the parameters and the requester cannot do that without knowing how
 * far over they were. A message saying only that a limit was exceeded turns into a support ticket.
 *
 * <p>The run is {@code FAILED} and its partial output has been deleted. There is deliberately no
 * option to keep the truncated file: a report that stops at an arbitrary row and is delivered
 * anyway is read as a complete answer to the question that was asked.
 */
@Getter
public class ReportLimitExceededException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String limitName;
    private final long limit;
    private final long observed;

    /**
     * Reports a limit that was passed.
     *
     * @param code     {@link ExportProblemCodes#ROW_LIMIT_EXCEEDED} or
     *                 {@link ExportProblemCodes#TIME_BUDGET_EXCEEDED}
     * @param limitName the configuration property that set the limit, so an operator can find it
     * @param limit    the limit
     * @param observed what was actually reached
     */
    public ReportLimitExceededException(String code, String limitName, long limit, long observed) {
        super(ProblemStatus.UNPROCESSABLE, code, limit, observed);
        this.limitName = limitName;
        this.limit = limit;
        this.observed = observed;
        withProperty("limit", limit);
        withProperty("observed", observed);
        withProperty("property", limitName);
    }
}
