package ru.ludwigandreas.security.authn.jwt;

import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.pat.claim.PatClaims;
import ru.ludwigandreas.pat.scope.PatAttenuation;
import ru.ludwigandreas.security.authn.attenuation.AttenuatedAuthentications;
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
 *
 * <h2>Personal access tokens: this is the only place the attenuation happens</h2>
 *
 * <p>An assertion the edge minted by exchanging a personal access token carries a {@code ludwig_pat} claim.
 * When it is present, the authorities resolved above are <b>intersected</b> with the token's scopes, and the
 * resulting authentication records which token was presented.
 *
 * <p>Three properties of that, and each is deliberate:
 *
 * <ul>
 *   <li><b>The owner side is still resolved live, by the same lookup, with no special case.</b> The claim
 *       narrows what was resolved; it never contributes to it. So a role revoked from the owner stops working
 *       for their tokens within the authority cache's TTL, with no token revocation and no call to the
 *       issuer - the same guarantee this module already gives for ordinary tokens, extended rather than
 *       excepted.</li>
 *   <li><b>The construction is delegated</b> to
 *       {@link ru.ludwigandreas.security.authn.attenuation.AttenuatedAuthentications}, which is the single
 *       site {@code credentials.one-attenuation-path} fences. It used to be this class; it was extracted
 *       when a second authentication path arrived, so that the rule could name one <em>type</em> rather
 *       than grow a second package. This converter computes no authority.</li>
 *   <li><b>A present but unreadable claim is rejected, not ignored.</b> See
 *       {@link #attenuationFor(Jwt)} - this is the one case where failing open hands over everything.</li>
 * </ul>
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
            throw new InvalidBearerTokenException(
                    "Token carries no '" + config.getSubjectClaim() + "' claim");
        }

        PrincipalType type = isServiceToken(jwt, config) ? PrincipalType.SERVICE : PrincipalType.USER;
        Authorities authorities = authorityLookup.lookup(new PrincipalRef(type, subject));

        // Resolved first, narrowed second, and never the other way round. The lookup above is identical for
        // a token-backed caller and a session-backed one; everything a personal access token does to this
        // request happens below, as a filter over what was just resolved.
        Optional<PatAttenuation> attenuation = attenuationFor(jwt);

        // The narrowing and the construction both happen in AttenuatedAuthentications, which is the
        // single place in the platform permitted to build a credential-backed authentication. This
        // converter supplies facts - the subject, the type, the display name, the LIVE authorities and
        // the token's scopes - and computes no authority of its own.
        LudwigPrincipal principal = AttenuatedAuthentications.principal(
                subject,
                type,
                claim(jwt, config.getNameClaim()),
                authorities,
                attenuation,
                // The resolver's tenant wins over the token's: the projection is this service's own
                // belief about the caller, and it can be corrected without waiting for a new token.
                // Passed as the fallback, which is what that precedence means.
                claim(jwt, config.getTenantClaim()));

        metrics.recordAuthenticated(type.name());

        if (attenuation.isEmpty()) {
            return AttenuatedAuthentications.direct(principal);
        }
        return AttenuatedAuthentications.attenuated(
                subject, type, claim(jwt, config.getNameClaim()), authorities,
                attenuation.get(), patId(jwt));
    }

    /**
     * The token's attenuation, or empty when this is not a token-backed assertion.
     *
     * <p><b>A present claim that cannot be read is a rejected request, not an unattenuated one.</b> That is
     * the whole content of this method and it is the one place in the converter where the fail-open default
     * would be catastrophic: an assertion that announces a personal access token but whose scope list is
     * malformed, truncated or of the wrong type would otherwise fall through to "no attenuation", and the
     * token would then carry its owner's entire authority - the exact privilege escalation the claim exists
     * to prevent, reachable by corrupting a list.
     *
     * <p>So the absence of the claim and the presence of an unreadable one are deliberately <em>not</em> the
     * same outcome, even though {@link PatClaims} returns {@code Optional.empty()} for both: this method
     * distinguishes them and throws for the second.
     */
    private Optional<PatAttenuation> attenuationFor(Jwt jwt) {
        if (!PatClaims.isPresent(jwt.getClaims())) {
            return Optional.empty();
        }
        Map<String, Object> claim = patClaim(jwt);
        Optional<PatAttenuation> attenuation = PatClaims.attenuation(claim);
        if (attenuation.isEmpty() || PatClaims.id(claim).isEmpty()) {
            metrics.recordAuthenticationFailed(PrincipalType.USER.name(), "malformed-pat-claim");
            throw new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException(
                    "Token carries a '" + PatClaims.CLAIM + "' claim that declares no usable scope or id."
                            + " Refused rather than treated as unattenuated: an assertion that announces a"
                            + " personal access token and cannot say what it is limited to would otherwise"
                            + " carry its owner's entire authority.");
        }
        return attenuation;
    }

    private String patId(Jwt jwt) {
        // Safe to call unguarded: attenuationFor has already thrown if the id is absent, and it runs first.
        return PatClaims.id(patClaim(jwt)).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> patClaim(Jwt jwt) {
        // The cast is guarded by PatClaims.isPresent, which checks the value is a Map before this runs.
        return (Map<String, Object>) jwt.getClaims().get(PatClaims.CLAIM);
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
