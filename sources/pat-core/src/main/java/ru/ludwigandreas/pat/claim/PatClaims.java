package ru.ludwigandreas.pat.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.pat.scope.PatAttenuation;

/**
 * The single definition of the {@code ludwig_pat} JWT claim, read by the issuer and the verifier alike.
 *
 * <p>This class exists because the two sides of the seam are in different modules and will be released
 * independently. The issuer - {@code pat-spring-boot-starter}, running inside the identity provider - writes
 * the claim; the verifier - {@code security-spring-boot-starter}, running inside all 29 consuming services -
 * reads it.
 *
 * <p><b>A claim name written as a literal on both sides is a claim name that will diverge</b>, and the
 * failure mode is not an error. A verifier looking for {@code ludwig_pat} in an assertion that carries
 * {@code ludwigPat} finds no claim, concludes the caller is an ordinary session-authenticated user, and
 * resolves their authorities <b>with no attenuation applied at all</b>. A token scoped to one read endpoint
 * then carries its owner's entire authority. That is a silent privilege escalation produced by a typo, it
 * fails open, and no test that uses a matching pair of modules will ever show it.
 *
 * <p>Hence one definition, in the module both sides already depend on, and hence {@code pat-core}'s zero
 * in-repo dependencies - without that property the security starter could not depend on this module and the
 * literal would have to be written twice.
 *
 * <h2>What the claim carries, and what it must never carry</h2>
 *
 * <p>It carries the token's identity and its attenuation: an id and a scope list. It carries <b>no roles and
 * no permissions</b>, and the issuer does not resolve the owner's authorities in order to mint it.
 *
 * <p>That is the same decision {@code LudwigPrincipal} documents for ordinary tokens, extended here. If the
 * issuer baked effective authority into a five-minute assertion, that assertion would be five minutes of
 * frozen privilege resolved from the issuer's view of the owner rather than from each service's own
 * projection - two sources of truth for entitlement, which is exactly the defect local role resolution exists
 * to prevent.
 */
public final class PatClaims {

    /**
     * The claim name.
     *
     * <p>Namespaced with the platform's own prefix rather than using a bare name like {@code pat}, so it
     * cannot collide with a claim the identity provider, a future standard or another tenant of the same
     * issuer might introduce.
     */
    public static final String CLAIM = "ludwig_pat";

    /** The token's id within the claim. Not the key id - see {@link #id(Map)}. */
    public static final String ID = "id";

    /** The attenuation scope list within the claim. */
    public static final String SCOPES = "scopes";

    private PatClaims() {
    }

    /**
     * Builds the claim value.
     *
     * <p>A {@code Map} rather than a typed object because this crosses a JWT serialization boundary where it
     * will be a JSON object regardless, and a type here would add a mapping step on both sides without adding
     * a guarantee - the verifier has to tolerate a malformed claim either way, since the claim arrives from
     * the network.
     *
     * @param patId  the token's stable id, which is what audit records and the management API name. This is
     *               deliberately <b>not</b> the key id: the key id is secret-adjacent lookup material that
     *               changes on rotation, while the id is the token's identity and survives it.
     * @param scopes the declared scopes, unintersected. The verifier intersects.
     */
    public static Map<String, Object> value(String patId, Collection<String> scopes) {
        if (patId == null || patId.isBlank()) {
            throw new IllegalArgumentException("patId must not be blank");
        }
        return Map.of(ID, patId, SCOPES, List.copyOf(PatAttenuation.of(scopes).scopes()));
    }

    /**
     * The token id from a claim value, or empty when the claim is absent or malformed.
     *
     * <p>Empty rather than an exception for a malformed claim, because this runs on every request of every
     * service and the input is from the network. A claim that cannot be read means "this is not a
     * token-backed request", and the request then proceeds as an ordinary authenticated one - which is safe,
     * because that path applies no attenuation to an authority the caller does not independently hold.
     */
    public static Optional<String> id(Map<String, Object> claim) {
        if (claim == null) {
            return Optional.empty();
        }
        Object value = claim.get(ID);
        return value instanceof String id && !id.isBlank() ? Optional.of(id) : Optional.empty();
    }

    /**
     * The attenuation from a claim value, or empty when the claim is absent or carries no usable scope.
     *
     * <p><b>Empty must be treated as "refuse", not as "no attenuation".</b> A caller that reads this as an
     * absent filter and proceeds with the owner's full authority has inverted the invariant: a claim that
     * announces a token but whose scopes could not be read is the one case where failing open hands over
     * everything. The verifier's contract is that a present {@code ludwig_pat} claim with an unreadable scope
     * list is a rejected request, and that is asserted by a test rather than left to this javadoc.
     */
    public static Optional<PatAttenuation> attenuation(Map<String, Object> claim) {
        if (claim == null) {
            return Optional.empty();
        }
        Object value = claim.get(SCOPES);
        if (!(value instanceof Collection<?> raw) || raw.isEmpty()) {
            return Optional.empty();
        }
        List<String> scopes = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element instanceof String scope && !scope.isBlank()) {
                scopes.add(scope);
            }
        }
        if (scopes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PatAttenuation.of(scopes));
    }

    /** Whether an assertion's claim set announces a token-backed request at all. */
    public static boolean isPresent(Map<String, Object> claims) {
        return claims != null && claims.get(CLAIM) instanceof Map;
    }
}
