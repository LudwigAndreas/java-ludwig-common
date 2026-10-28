package ru.ludwigandreas.security.web;

import ru.ludwigandreas.pat.problem.PatProblemTypes;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A token-backed caller reached a surface that refuses long-lived credentials: the token management surface, and
 * the administrative surface unless a deployment opens it to them.
 *
 * <p>Lives in the security starter, not in {@code pat-spring-boot-starter} where it was written, because two
 * surfaces in two modules raise it and both must answer with the same problem type; the PAT starter depends on this
 * one, so this is where both can reach it.
 *
 * <p>{@code 403} and not {@code 401}, and the distinction is load-bearing rather than pedantic: the caller's
 * identity is established and it is the <em>credential</em> that is refused. A {@code 401} tells a client to
 * re-authenticate, which it can do successfully with the same token and be refused again - a loop.
 *
 * <p>The rule behind it is that a token may not mint, rotate or revoke a token. Without it, a leaked
 * read-only token is a <b>persistence mechanism</b>: exchange it, call this API, mint a wider one, and
 * revoking the original accomplishes nothing. That is the single most valuable thing an attacker can do with
 * a narrow credential, so the surface refuses the credential rather than reasoning about its scopes.
 *
 * <p>The status comes from {@link ProblemStatus#FORBIDDEN} here rather than from a {@code @ResponseStatus}
 * annotation, which was the original approach and did not work: {@code web-core}'s shared advice catches the
 * exception before Spring consults the annotation and renders anything unmapped as a 500. In a service
 * running {@code web-core}, a module's statuses are whatever its exceptions and mappers say.
 */
public final class PatCredentialNotPermittedException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String credentialKind;

    public PatCredentialNotPermittedException(String credentialKind) {
        super(ProblemStatus.FORBIDDEN, "ludwig.pat.credential-not-permitted");
        this.credentialKind = credentialKind;
        withProperty("credentialKind", credentialKind);
        withProperty("manageCredentialsAt", PatProblemTypes.MANAGEMENT_PATH);
    }

    public String credentialKind() {
        return credentialKind;
    }
}
