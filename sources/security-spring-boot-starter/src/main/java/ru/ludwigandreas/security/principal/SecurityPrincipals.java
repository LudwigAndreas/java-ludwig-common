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

    public static boolean isType(PrincipalType type) {
        return current().map(principal -> principal.isType(type)).orElse(false);
    }
}
