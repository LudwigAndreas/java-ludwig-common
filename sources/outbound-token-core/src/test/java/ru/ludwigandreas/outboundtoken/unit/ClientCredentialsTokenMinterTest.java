package ru.ludwigandreas.outboundtoken.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import ru.ludwigandreas.outboundtoken.CachedToken;
import ru.ludwigandreas.outboundtoken.ClientCredentialsTokenMinter;
import ru.ludwigandreas.outboundtoken.TokenMintingException;

class ClientCredentialsTokenMinterTest {

    private static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId("catalog-sa")
            .clientId("catalog").clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("https://idp.example/token")
            .scope("read", "write")
            .build();

    private static OAuth2AccessTokenResponse response(String value, Instant expiresAt) {
        return OAuth2AccessTokenResponse.withToken(value).tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(expiresAt.getEpochSecond() - Instant.now().getEpochSecond()).build();
    }

    @Test
    @DisplayName("a minted token carries the value and the expiry the endpoint answered")
    void mints() {
        Instant expiry = Instant.now().plusSeconds(600);
        CachedToken token = new ClientCredentialsTokenMinter(REGISTRATION, null,
                request -> response("machine-1", expiry)).get();

        assertThat(token.value()).isEqualTo("machine-1");
        assertThat(token.expiresAt()).isCloseTo(expiry, org.assertj.core.api.Assertions.within(
                2, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("given scopes narrow the registration's for the request")
    void narrowsScopes() {
        AtomicReference<OAuth2ClientCredentialsGrantRequest> seen = new AtomicReference<>();
        new ClientCredentialsTokenMinter(REGISTRATION, List.of("read"), request -> {
            seen.set(request);
            return response("t", Instant.now().plusSeconds(60));
        }).get();

        assertThat(seen.get().getClientRegistration().getScopes()).containsExactly("read");
    }

    @Test
    @DisplayName("a refusing token endpoint is a TokenMintingException naming the registration and the code")
    void endpointRefuses() {
        ClientCredentialsTokenMinter minter = new ClientCredentialsTokenMinter(REGISTRATION, null, request -> {
            throw new OAuth2AuthorizationException(new OAuth2Error("invalid_client"));
        });

        assertThatThrownBy(minter::get).isInstanceOfSatisfying(TokenMintingException.class, refused -> {
            assertThat(refused.registrationId()).isEqualTo("catalog-sa");
            assertThat(refused.errorCode()).isEqualTo("invalid_client");
        }).hasMessageContaining("catalog-sa").hasMessageContaining("invalid_client");
    }

    @Test
    @DisplayName("an unknown registration id is refused, naming it")
    void unknownRegistration() {
        assertThatThrownBy(() -> ClientCredentialsTokenMinter.registration(
                new InMemoryClientRegistrationRepository(REGISTRATION), "missing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
        assertThat(ClientCredentialsTokenMinter.registration(
                new InMemoryClientRegistrationRepository(REGISTRATION), "catalog-sa")).isSameAs(REGISTRATION);
    }
}
