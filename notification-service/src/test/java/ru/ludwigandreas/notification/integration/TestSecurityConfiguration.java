package ru.ludwigandreas.notification.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Test-only wiring for the one thing the security stack needs from outside the process.
 *
 * <p>Replaces the decoder Spring Boot would build from {@code issuer-uri}, which performs OIDC
 * discovery when the bean is created - so without this the test would have to reach an identity
 * provider just to start the context.
 *
 * <p>It rejects every token, which is correct here: the tests authenticate by injecting a principal,
 * so a decoder that silently accepted anything would hide a genuine misconfiguration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new JwtException("No real tokens are issued in tests - see TestPrincipals");
        };
    }
}
