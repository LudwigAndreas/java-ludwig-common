package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.ludwigandreas.pat.claim.PatClaims;
import ru.ludwigandreas.security.authn.jwt.AudienceValidator;

/**
 * That an audience-scoped token assertion needs <b>no new verification code</b>.
 *
 * <p>The design claims this enforcement is free: the exchange mints an ordinary {@code aud} claim, and the
 * validator this module already ships refuses an assertion that does not name the service. This test is what
 * turns that claim into something checked, because "no code needed" is exactly the kind of assertion that is
 * true when written and false a release later.
 *
 * <p>It matters because of what audience binding is for. Scope answers <em>what</em> may be done; audience
 * answers <em>where</em>. An earlier draft of the design left the exchanged assertion's audience unspecified,
 * which would have handed this validator the very token its javadoc says must be refused: in a mesh where
 * every service trusts one provider, a token minted for the least sensitive service is otherwise valid at the
 * most sensitive one.
 */
class PatAudienceScopingTest {

    private static Jwt assertionFor(String audience) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("alice")
                .audience(List.of(audience))
                .claim(PatClaims.CLAIM, PatClaims.value("pat-1", List.of("orders:read")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    @DisplayName("an assertion naming this service is accepted, with no PAT-specific code involved")
    void acceptsItsOwnAudience() {
        AudienceValidator validator = new AudienceValidator(Set.of("deploy-service"));

        assertThat(validator.validate(assertionFor("deploy-service")).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("an assertion minted for another service is refused here")
    void refusesAnotherServicesAudience() {
        // The replay this prevents: a CI token issued for deployments, presented at billing. The
        // attenuation invariant means that is not privilege escalation - the token still cannot exceed its
        // owner - but for an owner holding broad roles, "bounded by the owner" is not a meaningful bound.
        AudienceValidator validator = new AudienceValidator(Set.of("billing-service"));

        assertThat(validator.validate(assertionFor("deploy-service")).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("an assertion with no audience at all is refused, which is what the earlier draft produced")
    void refusesAnAbsentAudience() {
        AudienceValidator validator = new AudienceValidator(Set.of("deploy-service"));

        Jwt noAudience = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("alice")
                .claim(PatClaims.CLAIM, PatClaims.value("pat-1", List.of("orders:read")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertThat(validator.validate(noAudience).hasErrors()).isTrue();
    }
}
