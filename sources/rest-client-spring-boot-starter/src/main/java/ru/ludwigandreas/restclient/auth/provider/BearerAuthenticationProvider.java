package ru.ludwigandreas.restclient.auth.provider;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.spi.AuthRequest;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;
import ru.ludwigandreas.restclient.spi.TokenSupplier;

/**
 * A bearer token: either a literal from configuration, or one a {@link TokenSupplier} bean produces.
 *
 * <p>The two are mutually exclusive and the constructor says so rather than picking a winner. A
 * precedence rule here - "the supplier wins if both are set" - is a rule nobody remembers, and the
 * failure it causes is a client silently ignoring the token an operator just rotated.
 */
public class BearerAuthenticationProvider implements ClientAuthenticationProvider {

    private final BeanFactory beanFactory;

    public BearerAuthenticationProvider(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public String type() {
        return AuthTypes.BEARER;
    }

    @Override
    public Class<? extends ClientAuthProperties> propertiesType() {
        return AuthProperties.class;
    }

    @Override
    public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        AuthProperties auth = (AuthProperties) properties;
        boolean hasToken = auth.getToken() != null && !auth.getToken().isBlank();
        boolean hasSupplier = auth.getTokenSupplier() != null && !auth.getTokenSupplier().isBlank();
        if (hasToken == hasSupplier) {
            throw new IllegalStateException("Client '" + clientName + "': auth.type=bearer requires "
                    + "exactly one of auth.token or auth.token-supplier"
                    + (hasToken ? ", not both." : "."));
        }
        String scheme = auth.getScheme() == null ? "Bearer" : auth.getScheme();
        return hasToken
                ? staticToken(scheme, auth.getToken())
                : suppliedToken(clientName, scheme, auth.getTokenSupplier());
    }

    private ClientAuthenticator staticToken(String scheme, String token) {
        String header = scheme + " " + token;
        return new ClientAuthenticator() {
            @Override
            public void authenticate(AuthRequest request) {
                request.headers().set("Authorization", header);
            }

            @Override
            public String describe() {
                return "bearer(static)";
            }
        };
    }

    private ClientAuthenticator suppliedToken(String clientName, String scheme, String beanName) {
        TokenSupplier supplier = resolve(clientName, beanName);
        return new ClientAuthenticator() {
            @Override
            public void authenticate(AuthRequest request) {
                String token = supplier.token(clientName, request.credentialRejected());
                if (token == null || token.isBlank()) {
                    // Deliberately a failure rather than an unauthenticated call. An empty
                    // Authorization header is accepted as anonymous by a surprising number of
                    // servers, which turns a missing credential into a silent privilege change.
                    throw new IllegalStateException("TokenSupplier '" + beanName
                            + "' returned no token for client '" + clientName + "'.");
                }
                request.headers().set("Authorization", scheme + " " + token);
            }

            @Override
            public boolean refreshOnUnauthorized() {
                // The supplier is told about the 401 through its forceRefresh flag, so one more
                // attempt can genuinely succeed - unlike the static case.
                return true;
            }

            @Override
            public String describe() {
                return "bearer(supplier=" + beanName + ")";
            }
        };
    }

    private TokenSupplier resolve(String clientName, String beanName) {
        try {
            return beanFactory.getBean(beanName, TokenSupplier.class);
        } catch (NoSuchBeanDefinitionException ex) {
            throw new IllegalStateException("Client '" + clientName + "': auth.token-supplier names "
                    + "bean '" + beanName + "', which is not a TokenSupplier bean in this context.", ex);
        }
    }
}
