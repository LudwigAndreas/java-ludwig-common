package ru.ludwigandreas.testsupport.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Replaces the {@link JwtDecoder} Spring Boot would build from {@code issuer-uri}.
 *
 * <h2>Why a test needs this at all</h2>
 *
 * <p>The decoder Boot builds from {@code issuer-uri} performs OIDC discovery when the bean is created,
 * so without this a test would have to reach the identity provider just to start the context.
 *
 * <p>Worth knowing beyond tests: so would the service. In production, prefer {@code jwk-set-uri}, which
 * is fetched lazily, if a service must be able to boot while the provider is unreachable.
 *
 * <h2>Why it rejects every token rather than accepting any</h2>
 *
 * <p>This is the part that is easy to get backwards. The tests authenticate by injecting a principal
 * (see {@link TestPrincipalBuilder}), so no test needs a token to be accepted - and a decoder that
 * silently accepted anything would hide a genuine misconfiguration, letting a test pass against a
 * security chain that in production would admit unsigned tokens. Rejecting everything means any code
 * path that unexpectedly tries to validate a token fails loudly and names itself.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * @Import(RejectingJwtDecoderConfiguration.class)
 * class SomeIntegrationTest { ... }
 * }</pre>
 *
 * <p>The {@code MockMvcBuilderCustomizer} that defaults every request to an admin caller is
 * deliberately <em>not</em> here - it lives in {@link DefaultCallerConfiguration}, because it existed in
 * only one of the two services and bundling it would change the other one's behaviour.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RejectingJwtDecoderConfiguration {

    /**
     * A decoder that refuses every token.
     *
     * @return the decoder
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new JwtException(
                    "No real tokens are issued in tests - authenticate by injecting a principal, "
                            + "see TestPrincipalBuilder");
        };
    }
}
