package ru.ludwigandreas.security.principal;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.security.exception.PrincipalUnavailableException;

/**
 * Reads the current {@link LudwigPrincipal} out of the {@code SecurityContext}.
 *
 * <p>Two accessors on purpose. {@link #current()} is for code that legitimately runs both
 * authenticated and not - an audit provider, a log enricher. {@link #require()} is for business code
 * that cannot be correct without a caller: it throws rather than returning a default, because the
 * failure mode of a silent fallback here is a query that quietly runs unscoped.
 *
 * <p>A plain static holder rather than injected beans: the same lookup has to work from a repository
 * fragment, a JPA listener and a MapStruct-generated mapper, none of which are good places to thread
 * a service through.
 */
public final class SecurityPrincipals {

    private SecurityPrincipals() {
    }

    public static Optional<LudwigPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof LudwigPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /**
     * @throws PrincipalUnavailableException when there is no authenticated caller - a programming
     *                                       error in code that only makes sense on a request thread
     */
    public static LudwigPrincipal require() {
        return current().orElseThrow(PrincipalUnavailableException::new);
    }

    public static Optional<String> currentSubject() {
        return current().map(LudwigPrincipal::subject);
    }

    /**
     * What the current caller presented, when it was a long-lived credential.
     *
     * <p>Empty for an ordinary request, which is most of them - so a caller can treat "present" as "this is
     * a token-backed request" without comparing against {@link CredentialKind#DIRECT}.
     *
     * <p>Lives here rather than being read with an {@code instanceof} at each use, because there are two
     * denial paths that need it - method security and the data-scope guard - and a second copy of the cast
     * is a second place to forget it. Both paths record the credential so that an investigation into a
     * denial ends at the owner's demotion rather than at the token: a personal access token confers the
     * intersection of its owner's <em>live</em> authority and its own scopes, so it can stop working with
     * nobody having revoked anything.
     */
    public static Optional<Credential> currentCredential() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof LudwigAuthentication ludwig && ludwig.credential().isLongLived()) {
            return Optional.of(ludwig.credential());
        }
        return Optional.empty();
    }

    public static boolean isType(PrincipalType type) {
        return current().map(principal -> principal.isType(type)).orElse(false);
    }
}
