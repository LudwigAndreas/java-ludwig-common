package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The requester no longer holds the access their run was accepted under.
 *
 * <p>A deferred run re-resolves authorities at execution time rather than trusting the snapshot
 * taken when it was requested. {@code identity-projection} evicts the authority cache on change
 * precisely so that a revocation takes effect in milliseconds, and a reporting engine that ignored
 * that would be the one path in the estate where a revoked permission still produced data - and the
 * highest-volume one, since a report is a bulk read by definition.
 *
 * <p>The run fails with this distinct status rather than quietly producing a narrower file, because
 * a file that silently lost its columns looks like a data problem to whoever receives it.
 */
@Getter
public class ReportAccessRevokedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String definitionKey;

    /**
     * Reports access lost between request and execution.
     *
     * @param definitionKey the report that can no longer be run, published as {@code definition}
     */
    public ReportAccessRevokedException(String definitionKey) {
        super(ProblemStatus.FORBIDDEN, ExportProblemCodes.ACCESS_REVOKED, definitionKey);
        this.definitionKey = definitionKey;
        withProperty("definition", definitionKey);
    }
}
