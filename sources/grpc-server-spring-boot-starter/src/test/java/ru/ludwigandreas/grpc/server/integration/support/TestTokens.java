package ru.ludwigandreas.grpc.server.integration.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * A resource server for tests: the token {@code valid-<subject>} is that subject, anything else is
 * rejected. The converter yields the platform's own authentication type, as
 * {@code JwtPrincipalConverter} does in a service.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

    private static final String PREFIX = "valid-";

    public static String bearer(String subject) {
        return "Bearer " + PREFIX + subject;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            if (!token.startsWith(PREFIX)) {
                throw new BadJwtException("not a test token");
            }
            return Jwt.withTokenValue(token).header("alg", "none").subject(token.substring(PREFIX.length())).build();
        };
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtConverter() {
        return jwt -> TestPrincipalBuilder.user(jwt.getSubject()).authentication();
    }
}
