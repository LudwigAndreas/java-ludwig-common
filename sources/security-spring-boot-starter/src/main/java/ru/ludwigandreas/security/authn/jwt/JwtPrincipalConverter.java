package ru.ludwigandreas.security.authn.jwt;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Turns a validated JWT into a {@link LudwigPrincipal}.
 *
 * <p>This is where the module's central claim - identity from the token, entitlement from the service -
 * actually happens. The JWT contributes {@code sub}, a display name and, if the deployment is
 * multi-tenant, a tenant claim. Roles are not read from it even if present: they are looked up through
 * {@link ru.ludwigandreas.security.authz.AuthorityResolver}. A token that arrives carrying a {@code roles} claim is therefore harmless,
 * which is a property worth having - it means nobody can widen their access by influencing token
 * issuance.
 *
 * <p>The same converter handles the two kinds of token this deployment issues. A user token comes from
 * the edge, which exchanged the browser's session cookie for it; a client-credentials token belongs to
 * a peer service. They are told apart by the presence of a subject that is also a registered client
 * ({@code ludwig.security.jwt.service-client-claim}), and each gets its own {@link PrincipalType} - so a
 * peer service can never pick up a data scope written for humans.
 */
@RequiredArgsConstructor
public class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AuthorityLookup authorityLookup;
    private final SecurityProperties properties;
    private final SecurityMetrics metrics;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        SecurityProperties.Jwt config = properties.getJwt();

        String subject = claim(jwt, config.getSubjectClaim());
        if (subject == null || subject.isBlank()) {
            metrics.recordAuthenticationFailed(PrincipalType.USER.name(), "missing-subject");
            throw new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException(
                    "Token carries no '" + config.getSubjectClaim() + "' claim");
        }

        PrincipalType type = isServiceToken(jwt, config) ? PrincipalType.SERVICE : PrincipalType.USER;
        Authorities authorities = authorityLookup.lookup(new PrincipalRef(type, subject));

        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject(subject)
                .type(type)
                .displayName(Objects.requireNonNullElse(claim(jwt, config.getNameClaim()), subject))
                // The resolver's tenant wins over the token's: the projection is this service's own
                // belief about the caller, and it can be corrected without waiting for a new token.
                .tenantId(authorities.attributes()
                        .getOrDefault(Authorities.TENANT_ATTRIBUTE, claim(jwt, config.getTenantClaim())))
                .roles(authorities.roles())
                .permissions(authorities.permissions())
                .attributes(authorities.attributes())
                .build();

        metrics.recordAuthenticated(type.name());
        return new LudwigAuthentication(principal);
    }

    /**
     * A client-credentials token has no human behind it. Recognizing that from the token rather than
     * from the route matters: the same endpoint is often called by both a user session and a peer
     * service, and they must not share a data scope.
     */
    private boolean isServiceToken(Jwt jwt, SecurityProperties.Jwt config) {
        String serviceClaim = config.getServiceClientClaim();
        if (serviceClaim == null || serviceClaim.isBlank()) {
            return false;
        }
        Object value = jwt.getClaim(serviceClaim);
        return value != null && !String.valueOf(value).isBlank();
    }

    private String claim(Jwt jwt, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Object value = jwt.getClaim(name);
        return value == null ? null : String.valueOf(value);
    }
}
