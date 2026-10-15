package ru.ludwigandreas.outboundtoken;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/**
 * Mints a machine token with the OAuth2 client-credentials grant of a
 * {@code spring.security.oauth2.client.registration}. Wrap it in a {@link CachingTokenSource}: on
 * its own it calls the token endpoint every time.
 *
 * <p>Uses the OAuth2 client, an optional dependency of this module; a service that never mints a
 * machine token never loads this class.
 */
public final class ClientCredentialsTokenMinter implements Supplier<CachedToken> {

    private final ClientRegistration registration;
    private final OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenClient;

    /**
     * Creates the minter with Spring Security's own token-response client.
     *
     * @param registration the registration to mint with
     * @param scopes       narrows the registration's scopes; {@code null} or empty keeps them
     */
    public ClientCredentialsTokenMinter(ClientRegistration registration, List<String> scopes) {
        this(registration, scopes, new DefaultClientCredentialsTokenResponseClient());
    }

    /**
     * Creates the minter with a given token-response client.
     *
     * @param registration the registration to mint with
     * @param scopes       narrows the registration's scopes; {@code null} or empty keeps them
     * @param tokenClient  what calls the token endpoint
     */
    public ClientCredentialsTokenMinter(ClientRegistration registration, List<String> scopes,
            OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenClient) {
        // Narrowing is legitimate and common: one service account may be entitled to more than a
        // particular dependency should be asked with.
        this.registration = scopes == null || scopes.isEmpty()
                ? registration
                : ClientRegistration.withClientRegistration(registration).scope(scopes).build();
        this.tokenClient = tokenClient;
    }

    /**
     * Looks a registration up by id.
     *
     * @param registrations  the application's registrations
     * @param registrationId the id
     * @return the registration
     * @throws IllegalArgumentException naming the id, when there is no such registration
     */
    public static ClientRegistration registration(ClientRegistrationRepository registrations, String registrationId) {
        ClientRegistration found = registrations.findByRegistrationId(registrationId);
        if (found == null) {
            throw new IllegalArgumentException("No spring.security.oauth2.client.registration." + registrationId);
        }
        return found;
    }

    /**
     * The registration actually used, with any narrowing applied.
     *
     * @return the registration
     */
    public ClientRegistration registration() {
        return registration;
    }

    /**
     * Mints a token.
     *
     * @return the token and its expiry
     * @throws TokenMintingException when the token endpoint refuses
     */
    @Override
    public CachedToken get() {
        try {
            OAuth2AccessTokenResponse response =
                    tokenClient.getTokenResponse(new OAuth2ClientCredentialsGrantRequest(registration));
            return new CachedToken(response.getAccessToken().getTokenValue(), response.getAccessToken().getExpiresAt());
        } catch (OAuth2AuthorizationException refused) {
            throw new TokenMintingException(
                    registration.getRegistrationId(), refused.getError().getErrorCode(), refused);
        }
    }
}
