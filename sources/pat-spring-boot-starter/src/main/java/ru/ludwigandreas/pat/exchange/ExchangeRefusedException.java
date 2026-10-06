package ru.ludwigandreas.pat.exchange;

import ru.ludwigandreas.pat.problem.PatProblemTypes;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * An exchange this issuer refuses.
 *
 * <p>Carries {@link #failure()} for metrics and audit. The <b>rendered response ignores it entirely</b> -
 * there is one code, one status and one body for all eleven causes, which is why the code is a constant here
 * rather than derived from the failure. {@code UniformExchangeFailureIT} compares the responses byte for byte
 * rather than trusting this comment.
 *
 * <p>One exception type with an enum rather than a hierarchy of typed exceptions, deliberately. A hierarchy
 * would be an invitation for somebody to add a mapper per subtype and render the distinction this endpoint
 * exists to conceal; with one type there is nothing to differentiate.
 *
 * <p>{@code 401} rather than {@code 403}: the credential was not accepted, so presenting a different one is
 * the remedy, which is what {@code 401} means.
 */
public class ExchangeRefusedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final transient ExchangeFailure failure;

    public ExchangeRefusedException(ExchangeFailure failure) {
        super(ProblemStatus.UNAUTHORIZED, "ludwig.pat.credential-rejected");
        this.failure = failure;
        // The management location is the only thing this response adds, and it is what makes the refusal
        // actionable for an automation user without disclosing which of eleven causes applied.
        withProperty("manageCredentialsAt", PatProblemTypes.MANAGEMENT_PATH);
    }

    /** For metrics and audit only. Never rendered. */
    public ExchangeFailure failure() {
        return failure;
    }
}
