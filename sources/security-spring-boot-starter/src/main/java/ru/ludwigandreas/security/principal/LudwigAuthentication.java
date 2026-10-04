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

    /**
     * What the caller presented on this request.
     *
     * <p>Not {@code transient}, for the same reason the principal is not: a transient field deserializes as
     * {@code null}, and a null credential would read as "direct" - turning a token-backed request into an
     * apparently session-backed one after a restart or a failover. That is precisely the direction the
     * failure must not go, since the management surface refuses token-backed callers and would start
     * admitting them.
     */
    private final Credential credential;

    /**
     * The ordinary case: an authentication with no long-lived credential behind it.
     *
     * <p>Retained unchanged, and that is the point of having two constructors rather than one with an extra
     * parameter. Every existing caller in this repository and in every consumer keeps compiling and linking
     * against this signature, so adding the credential dimension breaks nothing - whereas a new component on
     * {@link LudwigPrincipal}'s canonical constructor would have broken every consumer outside this
     * repository to carry a fact that does not belong on an identity.
     */
    public LudwigAuthentication(LudwigPrincipal principal) {
        this(principal, Credential.DIRECT);
    }

    /**
     * An authentication that records which long-lived credential the caller presented.
     *
     * <p>Used by the converter that reads a personal-access-token claim, which is the only place in the
     * platform permitted to construct a credential-backed authentication - enforced by
     * {@code credentials.one-attenuation-path} in {@code architecture-rules}, because the authorities on such
     * an authentication have already been narrowed to the intersection of the owner's live authorities and
     * the token's scopes, and a second construction site is where that gets written as a union instead.
     */
    public LudwigAuthentication(LudwigPrincipal principal, Credential credential) {
        super(authorities(principal));
        this.principal = Objects.requireNonNull(principal, "principal");
        this.credential = credential == null ? Credential.DIRECT : credential;
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

    /**
     * What the caller presented, as a kind and an id - never the credential material itself.
     *
     * <p>Distinct from {@link #getCredentials()}, which stays {@code null}. That method is Spring Security's
     * slot for the raw credential and it is deliberately empty here: an {@code Authentication} holding a
     * bearer token or a certificate tends to end up in a log line or an error payload. This returns a
     * description of the credential rather than the credential, which is why it can exist alongside that
     * decision rather than against it.
     */
    public Credential credential() {
        return credential;
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
