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
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.metrics.NoopSecurityMetrics;
import ru.ludwigandreas.security.principal.Delegation;
import ru.ludwigandreas.security.principal.LudwigAuthentication;

/** The delegation claim: read into the authentication as identity, never as authority. */
class JwtPrincipalConverterTest {

    private final SecurityProperties properties = new SecurityProperties();

    private LudwigAuthentication convert(Map<String, Object> claims) {
        Authorities owner = Authorities.builder().roles(Set.of("ROLE_READER")).permissions(Set.of()).build();
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return (LudwigAuthentication) new JwtPrincipalConverter(ref -> owner, properties, new NoopSecurityMetrics())
                .convert(builder.build());
    }

    @Test
    @DisplayName("a token without the claim is a direct request")
    void noClaim() {
        assertThat(convert(Map.of()).delegation()).isEmpty();
    }

    @Test
    @DisplayName("act.sub becomes the acting party, and act.roles grants nothing")
    void actingPartyIsIdentityOnly() {
        LudwigAuthentication authentication = convert(Map.of("act",
                Map.of("sub", "admin-console", "roles", List.of("ROLE_ADMIN"))));

        assertThat(authentication.delegation()).map(Delegation::actingParty).hasValue("admin-console");
        assertThat(authentication.getPrincipal().roles()).containsExactly("ROLE_READER");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_READER");
    }

    @Test
    @DisplayName("the claim name follows the identity provider, and blank turns the feature off")
    void configurableClaim() {
        properties.getJwt().setActorClaim("azp_actor");
        assertThat(convert(Map.of("azp_actor", Map.of("sub", "ops-portal"))).delegation())
                .map(Delegation::actingParty).hasValue("ops-portal");
        assertThat(convert(Map.of("act", "ignored now")).delegation()).isEmpty();

        properties.getJwt().setActorClaim("");
        assertThat(convert(Map.of("azp_actor", "anything")).delegation()).isEmpty();
    }

    @Test
    @DisplayName("a present but unreadable claim refuses the token")
    void malformedIsRefused() {
        assertThatThrownBy(() -> convert(Map.of("act", "admin-console")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("a personal-access-token assertion keeps its credential and gains the delegation")
    void withAttenuation() {
        LudwigAuthentication authentication = convert(Map.of(
                PatClaims.CLAIM, PatClaims.value("pat-1", List.of("ROLE_READER")),
                "act", Map.of("sub", "admin-console")));

        assertThat(authentication.credential().isLongLived()).isTrue();
        assertThat(authentication.delegation()).map(Delegation::actingParty).hasValue("admin-console");
    }
}
