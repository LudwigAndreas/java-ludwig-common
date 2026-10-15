package ru.ludwigandreas.outboundtoken.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.ludwigandreas.outboundtoken.InboundBearerToken;

class InboundBearerTokenTest {

    private static final Jwt JWT = Jwt.withTokenValue("inbound-token").header("alg", "none").subject("alice").build();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an authenticated bearer-token authentication yields its token")
    void authenticated() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(JWT, List.of()));

        assertThat(InboundBearerToken.current()).isEqualTo("inbound-token");
    }

    @Test
    @DisplayName("an unauthenticated one yields nothing")
    void unauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(JWT));

        assertThat(InboundBearerToken.current()).isNull();
    }

    @Test
    @DisplayName("no authentication, or one that keeps no token, yields nothing")
    void none() {
        assertThat(InboundBearerToken.current()).isNull();

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", null, "ROLE_X"));
        assertThat(InboundBearerToken.current()).isNull();
    }
}
