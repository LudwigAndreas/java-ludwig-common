package ru.ludwigandreas.pat.exchange;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.pat.claim.PatClaims;

/**
 * The shipped default minter: signs with an RSA key this module is given.
 *
 * <p>Deliberately the <em>fallback</em> rather than the intended production path. Where a deployment already
 * runs an OIDC provider, that provider owns the signing key and every service already trusts its issuer, so
 * a delegating {@link PatAssertionMinter} is strictly better - one key to rotate, one JWKS to publish. This
 * exists so that a deployment without one is not blocked, and so the shape of the SPI is proven by having
 * an implementation.
 *
 * <h2>What the claim set deliberately does not contain</h2>
 *
 * <p>No roles. No permissions. No {@code scope} in the OAuth sense. The {@code ludwig_pat} claim carries the
 * token's id and its <b>attenuation</b>, and each service intersects that with the owner's authorities
 * resolved live from its own projection.
 *
 * <p>This is the single most important property of the assertion and the easiest to "improve" away. Putting
 * effective authority in here would save every service a lookup and would make this five-minute assertion
 * five minutes of frozen privilege, resolved from the issuer's view of the owner rather than from each
 * service's - two sources of truth for entitlement, which is the defect local role resolution exists to
 * prevent. {@code TokenExchangeIT} asserts the absence rather than trusting this paragraph.
 *
 * <p>{@code aud} is a single-element list, never the token's whole audience set. An assertion naming several
 * audiences would be valid at all of them, which reintroduces in miniature the replay that
 * {@code AudienceValidator} exists to prevent.
 */
@RequiredArgsConstructor
public class JoseAssertionMinter implements PatAssertionMinter {

    private final RSAKey signingKey;

    private final String issuer;

    private final Clock clock;

    @Override
    public String mint(String ownerSubject, String patId, Set<String> scopes, String audience,
                       Duration lifetime) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(ownerSubject)
                // Exactly one. Not the token's set.
                .audience(List.of(audience))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                // A unique id per assertion, so a replay is at least identifiable after the fact. Not a
                // control on its own - nothing here keeps a replay list - but an assertion with no jti
                // cannot be correlated at all.
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim(PatClaims.CLAIM, PatClaims.value(patId, scopes))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (JOSEException cause) {
            // Not swallowed and not turned into a refusal. A signing failure is this issuer being broken,
            // not the caller's token being bad - rendering it as the uniform refusal would tell an operator
            // that tokens are being rejected when the truth is that none can be minted.
            throw new IllegalStateException(
                    "Could not sign a personal access token assertion. This is an issuer fault, not a"
                            + " credential fault: check the configured signing key.", cause);
        }
        return jwt.serialize();
    }
}
