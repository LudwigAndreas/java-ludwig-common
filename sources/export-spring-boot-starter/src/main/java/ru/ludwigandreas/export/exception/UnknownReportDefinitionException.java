package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A report was requested under a key no {@code ReportDefinitionSource} registered.
 *
 * <p>The registry validates everything it is given before the context starts, so at runtime this
 * means one of two things, and both are worth a specific answer rather than a generic 404: a caller
 * guessing at keys, or a saved configuration or subscription that outlived the definition it was
 * bound to. The second is the reason the key is published as a machine-readable member - an
 * operator reading the audit trail needs to see which key stopped existing.
 */
@Getter
public class UnknownReportDefinitionException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String definitionKey;

    /**
     * Reports a key nothing registered.
     *
     * @param definitionKey published as the {@code definition} member, so the client sees which one
     */
    public UnknownReportDefinitionException(String definitionKey) {
        super(ProblemStatus.NOT_FOUND, ExportProblemCodes.UNKNOWN_DEFINITION, definitionKey);
        this.definitionKey = definitionKey;
        withProperty("definition", definitionKey);
    }
}
