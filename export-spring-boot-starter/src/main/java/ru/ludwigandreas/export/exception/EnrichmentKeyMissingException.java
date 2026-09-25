package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A partner answered successfully and did not know a key a stage declared it could not do without.
 *
 * <p>Distinct from {@link EnrichmentFailedException} in exactly the way {@code MissingPolicy} is
 * distinct from {@code FailurePolicy}, and for the same reason: nothing went wrong with the partner.
 * Reaching this means a stage chose {@code MissingPolicy.failReport()}, which is the right choice
 * only when the keys are guaranteed by a foreign key somewhere - so hitting it says the guarantee
 * has been broken, and that is worth a loud failure rather than a placeholder.
 *
 * <p>The key itself is not published. It is production data, it would appear in a problem document
 * and in the logs, and the stage name plus the run's parameters are enough to find it.
 */
@Getter
public class EnrichmentKeyMissingException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String stageName;

    /**
     * Reports a key a required stage could not resolve.
     *
     * @param stageName the stage, published as {@code stage}
     */
    public EnrichmentKeyMissingException(String stageName) {
        super(ProblemStatus.UNPROCESSABLE, ExportProblemCodes.ENRICHMENT_KEY_MISSING, stageName);
        this.stageName = stageName;
        withProperty("stage", stageName);
    }
}
