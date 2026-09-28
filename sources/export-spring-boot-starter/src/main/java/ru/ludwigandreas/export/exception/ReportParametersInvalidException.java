package ru.ludwigandreas.export.exception;

import java.util.List;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.webcore.problem.Violation;

/**
 * The request's parameters could not be bound to the definition's parameter type, or failed its
 * constraints.
 *
 * <p>Carries every violation rather than the first, because somebody correcting a report request at a
 * form should see all of the mistakes at once - the same reasoning the startup validators use, one
 * layer out.
 *
 * <p>Rendered by {@code web-core}'s existing validation shape, so a parameter failure looks exactly
 * like a body-validation failure to a client that already handles one. A report's parameters are not
 * a special kind of input and should not need special client code.
 */
@Getter
public class ReportParametersInvalidException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    public ReportParametersInvalidException(List<Violation> violations, Throwable cause) {
        super(ProblemStatus.INVALID, ExportProblemCodes.PARAMETERS_INVALID, cause);
        this.violations = List.copyOf(violations);
        withProperty("violations", this.violations);
    }

    public ReportParametersInvalidException(Violation violation, Throwable cause) {
        this(List.of(violation), cause);
    }
}
