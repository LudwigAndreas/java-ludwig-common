package ru.ludwigandreas.restclient.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.restclient.core.PrincipalSupplier;

/**
 * Reads the audited principal from Spring Security's context.
 *
 * <p>The subject <em>name</em> and nothing else. {@code Authentication#getName} is the {@code sub}
 * claim for a JWT and the service-account name for a client-credentials token - a stable identifier
 * safe to retain. The principal <em>object</em> is not used, because for a
 * {@code JwtAuthenticationToken} it is the whole token, and a whole token in an audit store is a
 * retained credential.
 *
 * <p>An anonymous authentication is reported as no principal, not as {@code "anonymousUser"}: an
 * audit record saying a call was made by "anonymousUser" invites the reader to believe there was a
 * user.
 */
public class SecurityPrincipalSupplier implements PrincipalSupplier {

    private static final String ANONYMOUS = "anonymousUser";

    @Override
    public String principal() {
        Authentication authentication = SecurityContextHolder.getContext() == null
                ? null : SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String name = authentication.getName();
        return ANONYMOUS.equals(name) ? null : name;
    }
}
