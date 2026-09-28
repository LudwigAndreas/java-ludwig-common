package ru.ludwigandreas.restclient.auth.provider;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import reactor.core.publisher.Mono;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.spi.AuthRequest;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;
import ru.ludwigandreas.restclient.spi.ReactiveClientAuthenticator;

/**
 * Propagates the inbound user's access token to a downstream dependency.
 *
 * <h2>Why this one is the most dangerous provider here</h2>
 *
 * <p>Relaying a token hands this service's callers' credentials to a third party. That is sometimes
 * exactly right - a gateway calling a resource server on the user's behalf, where the downstream
 * must apply the user's own entitlements - and catastrophic when it is applied to a dependency that
 * should be called with the service's own identity, because the partner now holds a usable user
 * token it was never meant to see.
 *
 * <p>Three guards follow from that, and none of them is configurable away:
 *
 * <ul>
 *   <li>{@code auth.relay-enabled: true} must be set <em>on the client</em>. It is the one key the
 *       merger refuses to inherit from the {@code defaults} block, so nine clients cannot acquire it
 *       by accident.</li>
 *   <li>With no inbound token, the call <strong>fails</strong> by default rather than going out
 *       anonymously. Calling anonymously turns "this user was not authenticated" into "the call
 *       succeeded with whatever the partner grants anonymous callers" - a privilege change that
 *       produces no error anywhere and is invisible until an audit finds it.</li>
 *   <li>The startup validator refuses the client if the service is not a resource server, because a
 *       relay client in a service that never authenticates anyone can only ever fail closed.</li>
 * </ul>
 *
 * <p>It implements {@link ReactiveClientAuthenticator} because the security context of a reactive
 * request lives in the Reactor subscriber context, where a {@code CompletableFuture} cannot reach it.
 */
public class TokenRelayAuthenticationProvider implements ClientAuthenticationProvider {

    @Override
    public String type() {
        return AuthTypes.OAUTH2_TOKEN_RELAY;
    }

    @Override
    public Class<? extends ClientAuthProperties> propertiesType() {
        return AuthProperties.class;
    }

    @Override
    public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        AuthProperties auth = (AuthProperties) properties;
        if (!Boolean.TRUE.equals(auth.getRelayEnabled())) {
            throw new IllegalStateException("Client '" + clientName + "': auth.type=oauth2-token-relay "
                    + "requires auth.relay-enabled: true to be set on this client. Relaying a user's "
                    + "token is a per-dependency decision and is never inherited from the defaults "
                    + "block.");
        }
        boolean failClosed = !Boolean.FALSE.equals(auth.getFailWhenNoToken());
        return new RelayAuthenticator(clientName, failClosed);
    }

    private static final class RelayAuthenticator implements ReactiveClientAuthenticator {

        private final String clientName;
        private final boolean failWhenNoToken;

        private RelayAuthenticator(String clientName, boolean failWhenNoToken) {
            this.clientName = clientName;
            this.failWhenNoToken = failWhenNoToken;
        }

        @Override
        public void authenticate(AuthRequest request) {
            apply(request, SecurityContextHolder.getContext() == null
                    ? null : SecurityContextHolder.getContext().getAuthentication());
        }

        @Override
        public Mono<Void> authenticateReactive(AuthRequest request) {
            return ReactiveSecurityContextHolder.getContext()
                    .map(context -> (Authentication) context.getAuthentication())
                    // defaultIfEmpty is not usable with a null value, so the empty case is handled
                    // by switchIfEmpty on a Mono that applies "no authentication at all".
                    .flatMap(authentication -> Mono.fromRunnable(() -> apply(request, authentication)))
                    .switchIfEmpty(Mono.fromRunnable(() -> apply(request, null)))
                    .then();
        }

        private void apply(AuthRequest request, Authentication authentication) {
            String token = extractToken(authentication);
            if (token == null) {
                if (failWhenNoToken) {
                    throw new IllegalStateException("Client '" + clientName + "': no inbound access "
                            + "token to relay. The call was refused rather than made anonymously; set "
                            + "auth.fail-when-no-token: false only if an anonymous call to this "
                            + "dependency is genuinely safe.");
                }
                return;
            }
            request.headers().setBearerAuth(token);
        }

        private String extractToken(Authentication authentication) {
            if (authentication == null || !authentication.isAuthenticated()) {
                return null;
            }
            // Covers JwtAuthenticationToken and BearerTokenAuthentication, which is every shape a
            // resource server in this platform produces.
            if (authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> tokenAuthentication) {
                return tokenAuthentication.getToken().getTokenValue();
            }
            if (authentication.getCredentials() instanceof AbstractOAuth2Token token) {
                return token.getTokenValue();
            }
            return null;
        }

        @Override
        public boolean refreshOnUnauthorized() {
            // There is nothing to refresh: the token belongs to the caller, and this service cannot
            // mint a new one. Retrying with the same token can only produce the same 401.
            return false;
        }

        @Override
        public String describe() {
            return "oauth2-token-relay(failWhenNoToken=" + failWhenNoToken + ")";
        }
    }
}
