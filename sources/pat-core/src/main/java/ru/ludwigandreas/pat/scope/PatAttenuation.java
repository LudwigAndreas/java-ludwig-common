package ru.ludwigandreas.pat.scope;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A token's scope set, and the one operation that may be performed with it: intersection.
 *
 * <p>This is the type the whole capability turns on, and the thing to understand about it is what it
 * deliberately <b>cannot</b> do. There is no {@code union}, no {@code plus}, no {@code grant} and no
 * {@code withAdditional}. Not because nobody needed one, but because a scope set that can add to a caller's
 * authority is a scope set that has stopped being an attenuation - and the difference between
 * {@code retainAll} and {@code addAll} is one method name, invisible in review, and catastrophic.
 *
 * <h2>The invariant</h2>
 *
 * <p>A personal access token's effective authority is:
 *
 * <pre>
 *   effective = authoritiesOf(owner, now)  &#8745;  token.scopes()
 * </pre>
 *
 * <p>Never a union. Never a snapshot taken when the token was issued. The owner side is resolved live, per
 * request, through the same {@code AuthorityLookup} every other caller goes through.
 *
 * <p>{@code LudwigPrincipal} already states why: the identity provider issues identity, not entitlement, so
 * that a revoked role stops working within a cache TTL rather than a token lifetime, and so that a stolen
 * token cannot carry roles that were never granted. A token that froze its owner's roles at issuance would be
 * precisely the token-carried role that reasoning refuses, with a ninety-day lifetime attached.
 *
 * <p>Three consequences follow, and all three are intended:
 *
 * <ul>
 *   <li>A role revoked from the owner stops working for the owner's tokens with <b>no token revocation and no
 *       call to the issuer</b>.</li>
 *   <li>A token scoped to an authority its owner never held grants nothing. It is <b>inert rather than
 *       dangerous</b>, because filtering for an absent element yields the empty set.</li>
 *   <li>A token <b>can stop working without being revoked</b>. This is the whole security argument and it
 *       will read as a bug to whoever is paged, which is why the denial audit record names both the token and
 *       the absent authority - so the first investigation ends at the owner's demotion rather than at the
 *       token.</li>
 * </ul>
 *
 * <h2>Why the scopes are opaque strings</h2>
 *
 * <p>This module holds them as {@code String} and intersects them as {@code String}, rather than as the
 * security starter's {@code Authorities}. That is not laziness - it is what keeps {@code pat-core} at zero
 * in-repo dependencies, which is what lets {@code security-spring-boot-starter} depend on it without a
 * reactor cycle, which is what lets the claim name be defined once instead of written as a literal on both
 * sides of the seam. The typed intersection lives in the security starter, which owns {@code Authorities}.
 *
 * <p>The cost is that this type cannot validate that a scope names a real authority. That check belongs at
 * issuance - where the owner's current authorities are in hand - and not here, and it is a fail-fast nicety
 * rather than the enforcement: the enforcement is this intersection, at use time, every time.
 */
public final class PatAttenuation {

    private final Set<String> scopes;

    private PatAttenuation(Set<String> scopes) {
        this.scopes = scopes;
    }

    /**
     * An attenuation over the given scopes.
     *
     * <p>Refuses an empty set. A token that attenuates to nothing can authenticate but can do nothing, which
     * is indistinguishable from a leaked credential that happens to be harmless today - it is a configuration
     * mistake rather than a valid state, and accepting it here would mean the mistake is discovered by a
     * pipeline failing rather than by the request to create it being refused.
     */
    public static PatAttenuation of(Collection<String> scopes) {
        if (scopes == null) {
            throw new IllegalArgumentException("scopes must not be null");
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope != null && !scope.isBlank()) {
                copy.add(scope.trim());
            }
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "a personal access token must declare at least one scope - a token that attenuates to"
                            + " nothing is a configuration mistake, not a valid state");
        }
        return new PatAttenuation(Collections.unmodifiableSet(copy));
    }

    /** The declared scopes, unmodifiable. */
    public Set<String> scopes() {
        return scopes;
    }

    /**
     * The owner's authorities, narrowed to those this token also names.
     *
     * <p>The one operation. Note the direction: the result is built from {@code ownerAuthorities} and filtered
     * by {@code scopes}, so an element present in the scopes but absent from the owner's authorities cannot
     * appear in the output. Written this way rather than as {@code scopes.retainAll(owner)} because the
     * iteration order of the result then follows the owner's authorities, which is what a reader of an audit
     * record expects, and because starting from the scope set is one edit away from starting from a mutable
     * copy of it and adding.
     *
     * <p>A null or empty owner set yields the empty set, which is correct and is the disabled-owner case: an
     * owner with no authorities confers none, so their tokens are inert without anybody revoking anything.
     */
    public Set<String> intersect(Collection<String> ownerAuthorities) {
        if (ownerAuthorities == null || ownerAuthorities.isEmpty()) {
            return Set.of();
        }
        Set<String> effective = new LinkedHashSet<>();
        for (String authority : ownerAuthorities) {
            if (authority != null && scopes.contains(authority)) {
                effective.add(authority);
            }
        }
        return Collections.unmodifiableSet(effective);
    }

    /** Whether this token names the given authority at all, before any owner is considered. */
    public boolean names(String authority) {
        return authority != null && scopes.contains(authority);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PatAttenuation that && scopes.equals(that.scopes);
    }

    @Override
    public int hashCode() {
        return scopes.hashCode();
    }

    /** Safe to print: a scope set is not a secret, and seeing it in a log is usually the point. */
    @Override
    public String toString() {
        return "PatAttenuation" + scopes;
    }
}
