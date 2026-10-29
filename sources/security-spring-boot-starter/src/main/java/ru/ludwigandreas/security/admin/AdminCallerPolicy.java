package ru.ludwigandreas.security.admin;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.security.principal.Delegation;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.web.PatCredentialNotPermittedException;

/**
 * Who may use the administrative surface at all, on any transport, before any operation is considered: an
 * authenticated platform caller, never a partner, not on a personal access token unless the deployment allows it,
 * and a person only through one of the required acting parties.
 */
@Slf4j
public class AdminCallerPolicy {

    private final AdminAccessEvaluator evaluator;
    private final boolean allowPersonalAccessTokens;
    private final Set<String> requiredVia;

    /**
     * Creates the policy.
     *
     * @param evaluator                 publishes the acting-party denial
     * @param allowPersonalAccessTokens whether a PAT-credentialed caller may reach the surface
     * @param requiredVia               acting parties a person must come through; empty requires none
     */
    public AdminCallerPolicy(AdminAccessEvaluator evaluator, boolean allowPersonalAccessTokens,
                             Set<String> requiredVia) {
        this.evaluator = evaluator;
        this.allowPersonalAccessTokens = allowPersonalAccessTokens;
        this.requiredVia = Set.copyOf(requiredVia);
    }

    /**
     * Admits the caller, or throws the refusal.
     *
     * @param authentication the caller's authentication
     * @param target         what a denial names - the handler, or a gRPC service method
     * @param where          the call, for the log
     * @return the caller's authentication
     */
    public LudwigAuthentication admit(Authentication authentication, Object target, String where) {
        if (!(authentication instanceof LudwigAuthentication ludwig) || !ludwig.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("The administrative surface requires authentication");
        }
        LudwigPrincipal principal = ludwig.getPrincipal();
        if (principal.type() == PrincipalType.PARTNER) {
            log.warn("Refused partner {} on the administrative surface: {}", principal.subject(), where);
            throw new AccessDeniedException("Partners may not use the administrative surface");
        }
        if (ludwig.credential().isLongLived() && !allowPersonalAccessTokens) {
            log.warn("Refused a {} credential on the administrative surface: {}", ludwig.credential().kind(), where);
            throw new PatCredentialNotPermittedException(ludwig.credential().kind().name());
        }
        if (!requiredVia.isEmpty() && principal.type() == PrincipalType.USER
                && ludwig.delegation().map(Delegation::actingParty).filter(requiredVia::contains).isEmpty()) {
            log.warn("Refused {} on the administrative surface: not through {} ({})", principal.subject(),
                    requiredVia, where);
            evaluator.refuse(authentication, target, "People reach the administrative surface only through "
                    + requiredVia);
        }
        return ludwig;
    }
}
