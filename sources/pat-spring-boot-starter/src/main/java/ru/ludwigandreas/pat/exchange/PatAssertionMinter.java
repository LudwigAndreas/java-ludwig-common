package ru.ludwigandreas.pat.exchange;

import java.time.Duration;
import java.util.Set;

/**
 * Signs the short-lived assertion an exchanged token becomes.
 *
 * <p>An SPI with a shipped default, rather than this module owning signing outright. Where a deployment
 * already runs an OIDC provider, that provider holds the signing key and every service already trusts its
 * issuer - a second signing identity there would mean a second key to rotate, a second JWKS to publish and a
 * second thing for every resource server to trust. A delegating minter is strictly better, and this
 * interface is what makes it a later additive implementation rather than a rewrite.
 *
 * <p>This module is deliberately <b>not</b> becoming an authorization server. There is no discovery
 * document, no JWKS endpoint, no client registration and no other grant type. It verifies a token and asks
 * for an assertion.
 */
public interface PatAssertionMinter {

    /**
     * Mints a signed assertion.
     *
     * <p><b>The implementation must not resolve the owner's authorities.</b> The claim set it is asked for
     * carries identity and the attenuation, and that is the whole contract: baking effective authority into
     * a five-minute assertion would make it five minutes of frozen privilege resolved from the issuer's view
     * of the owner rather than from each service's own projection - two sources of truth for entitlement,
     * which is the defect local role resolution exists to prevent.
     *
     * @param ownerSubject the owner this assertion acts as
     * @param patId        the token's stable id, for the {@code ludwig_pat} claim
     * @param scopes       the attenuation, for the {@code ludwig_pat} claim. Never roles
     * @param audience     exactly one audience - never none, never the token's whole set
     * @param lifetime     how long the assertion is valid, which is also what the response declares as its
     *                     cacheable lifetime
     * @return the compact serialized assertion
     */
    String mint(String ownerSubject, String patId, Set<String> scopes, String audience, Duration lifetime);
}
