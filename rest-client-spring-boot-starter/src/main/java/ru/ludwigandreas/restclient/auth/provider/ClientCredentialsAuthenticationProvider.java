package ru.ludwigandreas.restclient.auth.provider;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import ru.ludwigandreas.restclient.auth.CachedToken;
import ru.ludwigandreas.restclient.auth.CachingTokenSource;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.spi.AuthRequest;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;

/**
 * OAuth2 client credentials, bound to a Spring Security {@code ClientRegistration}.
 *
 * <p>The registration is referenced by id rather than restated here. Client id and secret already
 * live under {@code spring.security.oauth2.client.registration}, already resolve through Vault, and
 * already have a rotation procedure; a second copy in {@code ludwig.rest-client} would be the copy
 * that does not get rotated.
 *
 * <p>Token handling is {@link CachingTokenSource}: cached, refreshed early by
 * {@code auth.refresh-skew}, minted exactly once however many callers need it at the same instant,
 * and discarded on a 401 so the retry that follows carries a freshly issued token.
 *
 * <p>Failures from the token endpoint surface as a {@code RestClientAuthenticationException} naming
 * the registration - not as a 401 from the partner, which is what a client that treats
 * "could not get a token" and "the partner rejected the token" as the same thing reports, and which
 * sends the wrong team to the wrong dashboard.
 */
public class ClientCredentialsAuthenticationProvider implements ClientAuthenticationProvider {

    private final ClientRegistrationRepository registrations;
    private final Executor refreshExecutor;
    private final Clock clock;
    private final TokenRefreshCounter refreshCounter;
    /** Creates the provider; the executor and clock are shared by every client it builds. */
    public ClientCredentialsAuthenticationProvider(ClientRegistrationRepository registrations,
                                                   Executor refreshExecutor, Clock clock,
                                                   TokenRefreshCounter refreshCounter) {
        this.registrations = registrations;
        this.refreshExecutor = refreshExecutor;
        this.clock = clock;
        this.refreshCounter = refreshCounter;
    }

    @Override
    public String type() {
        return AuthTypes.OAUTH2_CLIENT_CREDENTIALS;
    }

    @Override
    public Class<? extends ClientAuthProperties> propertiesType() {
        return AuthProperties.class;
    }

    @Override
    public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        AuthProperties auth = (AuthProperties) properties;
        ClientRegistration registration = resolveRegistration(clientName, auth);
        DefaultClientCredentialsTokenResponseClient tokenClient =
                new DefaultClientCredentialsTokenResponseClient();
        Duration skew = auth.getRefreshSkew();
        CachingTokenSource tokens = new CachingTokenSource(
                clientName,
                () -> mint(clientName, registration, tokenClient),
                skew,
                refreshExecutor,
                clock,
                () -> refreshCounter.tokenRefreshed(clientName, AuthTypes.OAUTH2_CLIENT_CREDENTIALS));
        return new ClientCredentialsAuthenticator(clientName, registration.getRegistrationId(), tokens);
    }

    private ClientRegistration resolveRegistration(String clientName, AuthProperties auth) {
        String id = auth.getRegistrationId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Client '" + clientName + "': "
                    + "auth.type=oauth2-client-credentials requires auth.registration-id.");
        }
        ClientRegistration registration = registrations.findByRegistrationId(id);
        if (registration == null) {
            throw new IllegalStateException("Client '" + clientName + "': auth.registration-id '" + id
                    + "' does not match any spring.security.oauth2.client.registration entry.");
        }
        if (auth.getScopes() != null && !auth.getScopes().isEmpty()) {
            // Narrowing the registration's scopes for one dependency is legitimate and common: the
            // same service account may be entitled to more than this particular client should ask for.
            return ClientRegistration.withClientRegistration(registration)
                    .scope(auth.getScopes())
                    .build();
        }
        return registration;
    }

    private CachedToken mint(String clientName, ClientRegistration registration,
                             DefaultClientCredentialsTokenResponseClient tokenClient) {
        try {
            OAuth2AccessTokenResponse response = tokenClient.getTokenResponse(
                    new OAuth2ClientCredentialsGrantRequest(registration));
            return new CachedToken(response.getAccessToken().getTokenValue(),
                    response.getAccessToken().getExpiresAt());
        } catch (OAuth2AuthorizationException ex) {
            throw new IllegalStateException("Client '" + clientName + "': the token endpoint of "
                    + "registration '" + registration.getRegistrationId() + "' refused: "
                    + ex.getError().getErrorCode(), ex);
        }
    }

    /** Named rather than anonymous so the reactive path can override {@code authenticateAsync}. */
    private static final class ClientCredentialsAuthenticator implements ClientAuthenticator {

        private final String clientName;
        private final String registrationId;
        private final CachingTokenSource tokens;

        private ClientCredentialsAuthenticator(String clientName, String registrationId,
                                               CachingTokenSource tokens) {
            this.clientName = clientName;
            this.registrationId = registrationId;
            this.tokens = tokens;
        }

        @Override
        public void authenticate(AuthRequest request) {
            CachedToken token = tokens.get(request.credentialRejected());
            request.headers().setBearerAuth(token.value());
        }

        @Override
        public CompletableFuture<Void> authenticateAsync(AuthRequest request) {
            // The cached-token case completes without touching the executor, so the reactive path
            // pays nothing in the normal case and never blocks in the exceptional one.
            return tokens.getAsync(request.credentialRejected())
                    .thenAccept(token -> request.headers().setBearerAuth(token.value()));
        }

        @Override
        public boolean refreshOnUnauthorized() {
            return true;
        }

        @Override
        public void invalidate() {
            tokens.invalidate();
        }

        @Override
        public String describe() {
            return "oauth2-client-credentials(registration=" + registrationId + ", client=" + clientName + ")";
        }
    }
}
