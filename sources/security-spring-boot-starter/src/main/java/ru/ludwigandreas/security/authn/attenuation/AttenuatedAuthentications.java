package ru.ludwigandreas.security.authn.attenuation;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.pat.scope.PatAttenuation;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The one place in the platform where a credential-backed authentication is built.
 *
 * <h2>Why this type exists at all</h2>
 *
 * <p>It was extracted from {@code JwtPrincipalConverter} when a second authentication path arrived.
 * Before that there was one caller, so the rule that fences the attenuation -
 * {@code credentials.one-attenuation-path} - could name the converter's package and be done.
 *
 * <p>A second path created a choice, and the two options were not equivalent. <b>Adding the new
 * package to the rule widens it:</b> two packages today, three next year, and the invariant is gone by
 * increments without anybody deciding to give it up. <b>Extracting the construction narrows it:</b> the
 * rule now names one type, which cannot be satisfied by adding a caller - only by routing through it.
 *
 * <p>So this class is the mechanism by which adding an authentication path does not weaken the thing
 * the last change built. Callers supply facts; they do not compute authority.
 *
 * <h2>The invariant, restated because this is where it lives</h2>
 *
 * <pre>
 *   effective = authoritiesOf(owner, now)  &#8745;  token.scopes()
 * </pre>
 *
 * <p>Never a union. Never a snapshot taken at issuance. {@code LudwigPrincipal}'s own javadoc gives the
 * reason: the identity provider issues identity, not entitlement, so that a revoked role stops working
 * within a cache TTL rather than a token lifetime. The intersection is one line and so is the union,
 * and the difference between them is invisible in review - which is the whole argument for there being
 * exactly one of them.
 */
public final class AttenuatedAuthentications {

    private AttenuatedAuthentications() {
    }

    /**
     * An ordinary authentication, with no long-lived credential behind it.
     *
     * <p>Here rather than at the call site so that <em>both</em> outcomes of a conversion come from one
     * place. A caller that built the unattenuated case itself and delegated only the attenuated one
     * would be a caller holding half the construction, and the next person to touch it would have to
     * work out which half.
     */
    public static LudwigAuthentication direct(LudwigPrincipal principal) {
        return new LudwigAuthentication(principal);
    }

    /**
     * An authentication narrowed by a personal access token's scopes.
     *
     * <p>The <b>only</b> way to obtain one. Takes the owner's authorities as resolved live by the
     * calling path - from a claim reader's {@code AuthorityLookup} call, or from a filter's - and the
     * token's declared scopes, and returns their intersection. It cannot be asked for a union, because
     * no such method exists here or on {@link PatAttenuation}.
     *
     * @param subject     the owner, who the request acts as
     * @param type        the principal type, unchanged by the credential - a token-backed caller is the
     *                    same person through the same door, which is why this is not a fourth type
     * @param displayName a label for logs and UIs, never an authorization input
     * @param authorities the owner's authorities, resolved <b>live</b> by the caller
     * @param attenuation the token's declared scopes
     * @param patId       the token's stable id, for the credential dimension and every audit record
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - six facts, each one the caller has and this type must not
    // re-derive. Grouping them into a holder would mean a second type describing a principal, which is
    // what LudwigPrincipal already is.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static LudwigAuthentication attenuated(String subject,
                                                   PrincipalType type,
                                                   String displayName,
                                                   Authorities authorities,
                                                   PatAttenuation attenuation,
                                                   String patId) {
        LudwigPrincipal principal = principal(subject, type, displayName, authorities,
                Optional.of(attenuation), null);
        return new LudwigAuthentication(principal, Credential.personalAccessToken(patId));
    }

    /**
     * Builds the principal, narrowing the authorities when an attenuation is present.
     *
     * <p>Public because the JWT converter needs the principal before it decides which authentication to
     * wrap it in - it carries a tenant claim this type has no business knowing about. The narrowing
     * still happens here, which is the part that matters; what the caller does with the result is
     * ordinary principal construction that every authentication path in the module already does.
     *
     * @param tenantFallback a tenant from the caller's own source, used only when the resolver supplied
     *                       none. The resolver wins, because the projection is this service's own belief
     *                       about the caller and can be corrected without waiting for a new token
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - see above.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static LudwigPrincipal principal(String subject,
                                             PrincipalType type,
                                             String displayName,
                                             Authorities authorities,
                                             Optional<PatAttenuation> attenuation,
                                             String tenantFallback) {
        Set<String> roles = authorities.roles();
        Set<String> permissions = authorities.permissions();
        if (attenuation.isPresent()) {
            roles = intersectRoles(attenuation.get(), roles);
            permissions = attenuation.get().intersect(permissions);
        }
        Map<String, String> attributes = authorities.attributes();
        return LudwigPrincipal.builder()
                .subject(subject)
                .type(type)
                .displayName(displayName == null || displayName.isBlank() ? subject : displayName)
                .tenantId(attributes.getOrDefault(Authorities.TENANT_ATTRIBUTE, tenantFallback))
                .roles(roles)
                .permissions(permissions)
                .attributes(attributes)
                .build();
    }

    /**
     * Intersects the owner's roles with the token's scopes, normalizing the scopes first.
     *
     * <p>The normalization is why roles cannot go through {@link PatAttenuation#intersect} directly.
     * {@link LudwigPrincipal} normalizes every role to the {@code ROLE_} prefix and
     * {@code hasRole('ADMIN')} tests for {@code ROLE_ADMIN}, so a token scoped {@code ADMIN} and one
     * scoped {@code ROLE_ADMIN} must mean the same thing. Without this the first intersects to nothing
     * and the symptom is a caller who visibly holds the role and is refused anyway - which reads as a
     * permission bug and gets "fixed" by granting more.
     *
     * <p>Permissions are deliberately <em>not</em> normalized: {@code orders:read} has no prefix
     * convention, and role normalization would turn it into {@code ROLE_orders:read} and match nothing.
     */
    private static Set<String> intersectRoles(PatAttenuation attenuation, Set<String> ownerRoles) {
        Set<String> normalizedScopes = new LinkedHashSet<>();
        for (String scope : attenuation.scopes()) {
            normalizedScopes.add(Authorities.normalizeRole(scope));
        }
        Set<String> effective = new LinkedHashSet<>();
        for (String role : ownerRoles) {
            if (normalizedScopes.contains(role)) {
                effective.add(role);
            }
        }
        return effective;
    }
}
