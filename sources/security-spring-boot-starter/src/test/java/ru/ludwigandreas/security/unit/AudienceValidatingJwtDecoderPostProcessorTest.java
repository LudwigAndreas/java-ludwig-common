package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import ru.ludwigandreas.security.authn.jwt.AudienceValidatingJwtDecoderPostProcessor;

/**
 * The audience check has to be installed on whatever decoder the application ends up with.
 *
 * <p>It used to be installed only on a {@code NimbusJwtDecoder}. A service configured by
 * {@code issuer-uri} - which is every deployed service - gets a lazy {@code SupplierJwtDecoder} bean
 * from Spring Boot instead, so the check was silently skipped and a token the same issuer minted for
 * another service was accepted. The decoder in these tests is deliberately not a Nimbus one: it stands
 * in for that bean, and for a decoder an application supplies itself.
 */
class AudienceValidatingJwtDecoderPostProcessorTest {

    private static final String ISSUER = "https://issuer.example";

    private final AudienceValidatingJwtDecoderPostProcessor postProcessor =
            new AudienceValidatingJwtDecoderPostProcessor(ISSUER, Set.of("notification-service"));

    @Test
    @DisplayName("a token for another audience is rejected by a decoder that is not a NimbusJwtDecoder")
    void rejectsAnotherAudience() {
        JwtDecoder decoder = processed(tokenFor(List.of("some-other-service")));

        assertThatThrownBy(() -> decoder.decode("token"))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("audience");
    }

    @Test
    @DisplayName("a token with no audience at all is rejected")
    void rejectsAMissingAudience() {
        JwtDecoder decoder = processed(tokenFor(null));

        assertThatThrownBy(() -> decoder.decode("token")).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("a token for a configured audience is accepted, among others it may carry")
    void acceptsAConfiguredAudience() {
        Jwt token = tokenFor(List.of("some-other-service", "notification-service"));

        assertThat(processed(token).decode("token")).isSameAs(token);
    }

    @Test
    @DisplayName("with no audience configured the decoder is left exactly as it was")
    void leavesTheDecoderAloneWhenNothingIsConfigured() {
        JwtDecoder original = raw -> tokenFor(List.of("anything"));

        Object result = new AudienceValidatingJwtDecoderPostProcessor(ISSUER, Set.of())
                .postProcessAfterInitialization(original, "jwtDecoder");

        assertThat(result).isSameAs(original);
    }

    @Test
    @DisplayName("a bean that is not a decoder is not touched")
    void ignoresOtherBeans() {
        Object bean = new Object();

        assertThat(postProcessor.postProcessAfterInitialization(bean, "something")).isSameAs(bean);
    }

    private JwtDecoder processed(Jwt decoded) {
        JwtDecoder notNimbus = raw -> decoded;
        return (JwtDecoder) postProcessor.postProcessAfterInitialization(notNimbus, "jwtDecoder");
    }

    private static Jwt tokenFor(List<String> audience) {
        Jwt.Builder token = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("someone")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (audience != null) {
            token.audience(audience);
        }
        return token.build();
    }
}
