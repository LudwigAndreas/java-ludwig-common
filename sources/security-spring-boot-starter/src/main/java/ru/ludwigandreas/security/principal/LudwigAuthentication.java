package ru.ludwigandreas.security.principal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The {@link org.springframework.security.core.Authentication} this module puts in the
 * {@code SecurityContext}, for all three principal types alike.
 *
 * <p>It is always constructed already-authenticated: credential verification happened before this
 * object exists (Nimbus validated the JWT signature against the OIDC provider's JWKS; Envoy
 * completed the TLS handshake and validated the client chain). What this type adds is the
 * normalized {@link LudwigPrincipal} and the authorities derived from it, so
 * {@code @PreAuthorize("hasRole('...')")} and {@link ru.ludwigandreas.security.data.DataAccessGuard}
 * read from the same source.
 *
 * <p>{@link #getCredentials()} deliberately returns {@code null} rather than the bearer token or the
 * certificate: nothing downstream needs the raw credential, and an {@code Authentication} that holds
 * one tends to end up in a log line or an error payload. The verified facts are on the principal.
 */
public class LudwigAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /**
     * Not {@code transient}: {@code AbstractAuthenticationToken} is {@link java.io.Serializable}, and a
     * transient principal would deserialize as {@code null} - an authentication that passes
     * {@code isAuthenticated()} while every authorization check reading the principal throws or denies.
     * The module's own filter chain is stateless and never serializes it, but nothing stops a consumer
     * from enabling sessions, and the failure would only appear after a restart or a failover.
     */
    private final LudwigPrincipal principal;

    public LudwigAuthentication(LudwigPrincipal principal) {
        super(authorities(principal));
        this.principal = Objects.requireNonNull(principal, "principal");
        setAuthenticated(true);
    }

    /**
     * Roles and permissions become one flat authority list, which is what Spring Security's
     * expression language works with: {@code hasRole('CATALOG_ADMIN')} matches the
     * {@code ROLE_CATALOG_ADMIN} entries, {@code hasAuthority('order:read')} the permission entries.
     */
    private static Collection<GrantedAuthority> authorities(LudwigPrincipal principal) {
        if (principal == null) {
            return List.of();
        }
        return java.util.stream.Stream.concat(principal.roles().stream(), principal.permissions().stream())
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    @Override
    public LudwigPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    /** The audited actor - see {@code SpringSecurityAuditorProvider} in db-core. */
    @Override
    public String getName() {
        return principal.subject();
    }
}
