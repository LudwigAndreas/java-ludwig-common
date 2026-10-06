package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import ru.ludwigandreas.pat.claim.PatClaims;
import ru.ludwigandreas.security.authn.jwt.JwtPrincipalConverter;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.metrics.NoopSecurityMetrics;
import ru.ludwigandreas.security.principal.CredentialKind;
import ru.ludwigandreas.security.principal.LudwigAuthentication;

/**
 * The attenuation invariant, proven through the converter that is the only place it happens.
 *
 * <p>{@code PatAttenuationTest} in {@code pat-core} proves the set operation. This proves the thing that
 * actually matters: that the converter applies it to authorities resolved <em>live</em>, that a malformed
 * claim fails closed, and that the role-prefix normalization does not silently make every role-scoped token
 * inert.
 */
class PatAttenuatingConverterTest {

    private static final String SUBJECT = "alice";

    /** Authorities the lookup returns, mutable so a test can demote the owner between conversions. */
    private Authorities owner = authorities(Set.of("ROLE_ADMIN", "ROLE_READER"), Set.of("orders:read"));

    private static Authorities authorities(Set<String> roles, Set<String> permissions) {
        return Authorities.builder().roles(roles).permissions(permissions).build();
    }

    private JwtPrincipalConverter converter() {
        AuthorityLookup lookup = ref -> owner;
        return new JwtPrincipalConverter(lookup, new SecurityProperties(), new NoopSecurityMetrics());
    }

    private static Jwt jwt(Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(SUBJECT)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        extraClaims.forEach(builder::claim);
        return builder.build();
    }

    private static Jwt tokenBacked(String patId, List<String> scopes) {
        return jwt(Map.of(PatClaims.CLAIM, PatClaims.value(patId, scopes)));
    }

    private LudwigAuthentication convert(Jwt jwt) {
        return (LudwigAuthentication) converter().convert(jwt);
    }

    @Test
    @DisplayName("an assertion with no claim is unchanged - no attenuation, no credential")
    void noClaimMeansNoAttenuation() {
        LudwigAuthentication authentication = convert(jwt(Map.of()));

        assertThat(authentication.getPrincipal().roles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_READER");
        assertThat(authentication.getPrincipal().permissions()).containsExactly("orders:read");
        assertThat(authentication.credential().isLongLived()).isFalse();
    }

    @Test
    @DisplayName("the effective authority is the owner's narrowed by the scopes, and the token is recorded")
    void intersectsAndRecordsTheCredential() {
        LudwigAuthentication authentication =
                convert(tokenBacked("pat-1", List.of("ROLE_READER", "orders:read")));

        assertThat(authentication.getPrincipal().roles()).containsExactly("ROLE_READER");
        assertThat(authentication.getPrincipal().permissions()).containsExactly("orders:read");
        assertThat(authentication.credential().isKind(CredentialKind.PERSONAL_ACCESS_TOKEN)).isTrue();
        assertThat(authentication.credential().credentialId()).contains("pat-1");
    }

    @Test
    @DisplayName("a demoted owner loses the authority with no revocation, because the owner side is live")
    void demotedOwnerLosesTheAuthority() {
        Jwt admin = tokenBacked("pat-1", List.of("ROLE_ADMIN"));

        assertThat(convert(admin).getPrincipal().roles()).containsExactly("ROLE_ADMIN");

        // The demotion. Nothing about the token changes - not its scopes, not its validity, not its claim.
        owner = authorities(Set.of("ROLE_READER"), Set.of());

        assertThat(convert(admin).getPrincipal().roles()).isEmpty();
    }

    @Test
    @DisplayName("a scope naming an authority the owner never held grants nothing")
    void scopeForAnAbsentAuthorityGrantsNothing() {
        assertThat(convert(tokenBacked("pat-1", List.of("ROLE_SUPERUSER"))).getPrincipal().roles()).isEmpty();
    }

    @Test
    @DisplayName("an authority granted after issuance becomes effective without reissuing the token")
    void ownerGainingAnAuthorityTakesEffect() {
        Jwt token = tokenBacked("pat-1", List.of("orders:write"));

        assertThat(convert(token).getPrincipal().permissions()).isEmpty();

        owner = authorities(Set.of("ROLE_READER"), Set.of("orders:read", "orders:write"));

        assertThat(convert(token).getPrincipal().permissions()).containsExactly("orders:write");
    }

    @Test
    @DisplayName("an unprefixed role scope matches, so a token scoped ADMIN is not silently inert")
    void roleScopesAreNormalized() {
        // The trap LudwigPrincipal's constructor documents, one level out. hasRole('ADMIN') tests for
        // ROLE_ADMIN, so a token scoped "ADMIN" has to mean the same as one scoped "ROLE_ADMIN" - otherwise
        // the symptom is a caller who visibly holds the role and is refused anyway.
        assertThat(convert(tokenBacked("pat-1", List.of("ADMIN"))).getPrincipal().roles())
                .containsExactly("ROLE_ADMIN");
        assertThat(convert(tokenBacked("pat-1", List.of("ROLE_ADMIN"))).getPrincipal().roles())
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("permissions are not role-normalized, which would make every permission scope inert")
    void permissionScopesAreNotNormalized() {
        assertThat(convert(tokenBacked("pat-1", List.of("orders:read"))).getPrincipal().permissions())
                .containsExactly("orders:read");
    }

    @Test
    @DisplayName("the effective authority never exceeds the owner's, whatever the scopes claim")
    void neverExceedsTheOwner() {
        LudwigAuthentication authentication = convert(tokenBacked("pat-1",
                List.of("ROLE_ADMIN", "ROLE_READER", "ROLE_SUPERUSER", "orders:read", "orders:delete")));

        assertThat(authentication.getPrincipal().roles())
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_READER");
        assertThat(authentication.getPrincipal().permissions()).containsExactly("orders:read");
    }

    @Test
    @DisplayName("a present claim with no usable scope is REFUSED, not treated as unattenuated")
    void malformedClaimFailsClosed() {
        // The assertion this class exists for. Falling through to "no attenuation" here would hand the
        // token its owner's entire authority, reachable by corrupting a list - so the absence of the claim
        // and the presence of an unreadable one must not be the same outcome.
        for (Object badScopes : List.of(List.of(), List.of(1, 2), List.of("", "  "), "not-a-list")) {
            Jwt malformed = jwt(Map.of(PatClaims.CLAIM,
                    Map.of(PatClaims.ID, "pat-1", PatClaims.SCOPES, badScopes)));

            assertThatThrownBy(() -> convert(malformed))
                    .as("scopes=%s", badScopes)
                    .isInstanceOf(InvalidBearerTokenException.class);
        }
    }

    @Test
    @DisplayName("a present claim with no id is refused, because such a request is unauditable")
    void claimWithoutAnIdFailsClosed() {
        Jwt malformed = jwt(Map.of(PatClaims.CLAIM, Map.of(PatClaims.SCOPES, List.of("ROLE_READER"))));

        assertThatThrownBy(() -> convert(malformed)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("a claim of the wrong type is not a token-backed request and is simply ignored")
    void wronglyTypedClaimIsNotTokenBacked() {
        // Distinct from the case above on purpose. A claim whose value is not an object at all cannot have
        // been minted by the exchange, so it is noise rather than a corrupted attenuation - and treating it
        // as a refusal would let anyone deny service to a user by adding a junk claim upstream.
        LudwigAuthentication authentication = convert(jwt(Map.of(PatClaims.CLAIM, "junk")));

        assertThat(authentication.credential().isLongLived()).isFalse();
        assertThat(authentication.getPrincipal().roles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_READER");
    }
}
