package ru.ludwigandreas.pat.service;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * An issuance or management request this issuer refuses.
 *
 * <p>Extends {@code web-core}'s {@link LocalizedException}, which is the platform's base for anything the
 * shared RFC 9457 pipeline should render. That was a correction: these started as plain
 * {@code RuntimeException}s with a hand-written {@code ExceptionProblemMapper} beside them, and
 * {@code architecture-rules}' {@code exceptions.custom-exceptions-extend-the-base-exception} refused it.
 *
 * <p>The rule was right and the fix removed more than it added. {@link LocalizedException} already carries
 * the status, the message code, its arguments and the machine-readable properties, and {@code web-core}
 * renders it natively - so an entire mapper class and three bean definitions went away, along with a
 * {@code problem -> web} package dependency that was part of a cycle the same run reported. Three findings,
 * one cause.
 *
 * <p>Deliberately <b>not</b> used for an exchange failure. Every exchange failure produces one
 * indistinguishable response, so it has no per-cause message to carry - see {@code ExchangeRefusedException}.
 */
public class PatException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public PatException(ProblemStatus status, String code, Object... args) {
        super(status, code, args);
    }

    /** An issuance refused by policy - a lifetime past the ceiling, an empty scope set, no audience. */
    public static PatException issuanceRefused(String code, Object... args) {
        return new PatException(ProblemStatus.INVALID, code, args);
    }

    /**
     * A token that does not exist, or that the caller may not see.
     *
     * <p>One exception for both, deliberately: distinguishing them would turn the endpoint into an oracle
     * for whether a given token id exists, and a token id appears in audit records and support tickets.
     */
    public static PatException notFound() {
        return new PatException(ProblemStatus.NOT_FOUND, "ludwig.pat.not-found");
    }
}
