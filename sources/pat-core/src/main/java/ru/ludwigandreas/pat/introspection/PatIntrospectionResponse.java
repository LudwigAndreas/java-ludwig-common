package ru.ludwigandreas.pat.introspection;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What the issuing service answers when asked about a token.
 *
 * <p>Defined here, in the module both sides already depend on, for the same reason {@code PatClaims} is:
 * the issuer and every verifier are released independently, and a field name written twice is a field
 * name that diverges. The failure mode here is milder than the claim's - a verifier reading the wrong
 * field gets a null and refuses the request, which fails closed - but it is still a defect nobody would
 * find by testing either side alone.
 *
 * <h2>Why the component names are RFC 7662's rather than readable Java</h2>
 *
 * <p>{@code sub}, {@code aud}, {@code exp} rather than {@code subject}, {@code audiences},
 * {@code expiresAt}. Not a style choice: <b>{@code pat-core} has zero dependencies, including no
 * Jackson</b>, so there is no {@code @JsonProperty} available to bridge a readable Java name to a wire
 * name. The component names <em>are</em> the wire names.
 *
 * <p>That constraint is load-bearing rather than incidental. This module is depended on from both sides
 * of the security seam, and {@code security-spring-boot-starter} - which sits near the bottom of the
 * reactor - can only depend on it because it drags nothing along. An attempt to add Jackson here failed
 * the build, which is the property working. The alternative, mapping readable names to wire names in the
 * starter, would have put the field names in two places, which is the one thing this type exists to
 * prevent.
 *
 * <p>RFC names at a wire seam are also defensible on their own: a consumer configured against the
 * standard should not have to learn a local dialect.
 *
 * <p>{@code patId} is this platform's addition, because the RFC has no field for "which credential was
 * this" - and it is the one field here that is camelCase rather than snake_case. That looks inconsistent
 * beside {@code sub} and {@code exp} and is not: those are single words, so the RFC's fields have no case
 * to be consistent with. Rendering this one as {@code pat_id} would have meant a record component named
 * {@code pat_id}, which is legal Java and would need a Checkstyle naming suppression - a suppression spent
 * on the appearance of a field the standard does not define.
 *
 * <h2>What this deliberately does not carry</h2>
 *
 * <p><b>No secret and no digest.</b> Asserted by a test over the record's components rather than left to
 * inspection, so a field added later is covered on the day it is written.
 *
 * <p><b>No key id</b>, which is less obvious and is the decision every other response in this platform
 * makes. The key id is secret-adjacent lookup material that <em>changes on rotation</em>, so it is
 * useless to a caller naming a token and would quietly become the identifier somebody built an
 * integration on. {@code patId} survives a rotation, which is what a caller and an audit record both
 * need.
 *
 * @param active whether the token may be used right now. {@code false} is the <b>only</b> thing a failed
 *               introspection says - see {@link #inactive()}
 * @param sub    the owner, who a request authenticated by this token acts as
 * @param scope  the attenuation, space-delimited as RFC 7662 and OAuth both specify
 * @param aud    the services this token may be presented to
 * @param patId  the token's stable id, for the credential dimension and every audit record
 * @param exp    when the token stops working, or {@code null} for a deployment that has explicitly
 *               enabled non-expiring tokens
 */
public record PatIntrospectionResponse(
        boolean active,
        String sub,
        String scope,
        List<String> aud,
        String patId,
        Instant exp) {

    public PatIntrospectionResponse {
        aud = aud == null ? null : List.copyOf(aud);
    }

    /**
     * The single response every failure produces.
     *
     * <p>A constant factory rather than per-cause construction, so that no code path can accidentally
     * render an inactive response carrying one more field than another. Every cause - an unknown key id,
     * a bad secret, a malformed token, a revoked token, an expired token, a refused source - answers with
     * this.
     *
     * <p>Anything that distinguished them would be an oracle: "unknown key id" tells an attacker which
     * half of their guess to keep working on, and "revoked" confirms a harvested token was once real and
     * names a live target. The defender reads the cause in the issuer's metrics, where the attacker
     * cannot.
     */
    public static PatIntrospectionResponse inactive() {
        return new PatIntrospectionResponse(false, null, null, null, null, null);
    }

    /** An active response for a verified token. */
    public static PatIntrospectionResponse active(String owner, Set<String> scopes,
                                                   Set<String> audiences, String patId,
                                                   Instant expiresAt) {
        return new PatIntrospectionResponse(
                true, owner, String.join(" ", scopes), List.copyOf(audiences), patId, expiresAt);
    }

    /** The scope list as a set, for the intersection. Empty for an inactive response. */
    public Set<String> scopes() {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Set.of(scope.trim().split("\\s+"));
    }

    /** Whether this token may be presented to the given service. */
    public boolean permits(String service) {
        return aud != null && service != null && aud.contains(service);
    }
}
