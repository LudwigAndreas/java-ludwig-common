package ru.ludwigandreas.example.catalog.integration;

import static ru.ludwigandreas.example.catalog.integration.TestPrincipals.admin;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Test-only wiring for the two things the security stack needs from outside the process.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    /**
     * Replaces the decoder Spring Boot would build from {@code issuer-uri}.
     *
     * <p>That one performs OIDC discovery when the bean is created, so without this the test would need
     * to reach the identity provider just to start the context - and so, incidentally, would the service
     * itself. (In production that is a real consideration: prefer {@code jwk-set-uri}, which is fetched
     * lazily, if a service must be able to boot while the provider is unreachable.)
     *
     * <p>It rejects every token, which is correct here: the tests authenticate by injecting a principal,
     * so a decoder that silently accepted anything would hide a genuine misconfiguration.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new JwtException("No real tokens are issued in tests - see TestPrincipals");
        };
    }

    /**
     * Runs every request as the admin unless the request says otherwise. Tests that care about a
     * narrower caller add their own {@code .with(...)}, which is applied after this default and wins.
     */
    @Bean
    public MockMvcBuilderCustomizer defaultCaller() {
        return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/").with(admin()));
    }
}
