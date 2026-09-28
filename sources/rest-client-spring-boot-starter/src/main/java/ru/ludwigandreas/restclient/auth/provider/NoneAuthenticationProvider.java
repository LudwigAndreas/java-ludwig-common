package ru.ludwigandreas.restclient.auth.provider;

import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.spi.AuthRequest;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;

/**
 * The default: no credentials at all.
 *
 * <p>It is a real provider rather than a {@code null} authenticator so that the pipeline has exactly
 * one code path. A null check on the request path is a null check that gets forgotten.
 */
public class NoneAuthenticationProvider implements ClientAuthenticationProvider {

    @Override
    public String type() {
        return AuthTypes.NONE;
    }

    @Override
    public Class<? extends ClientAuthProperties> propertiesType() {
        return AuthProperties.class;
    }

    @Override
    public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        return new ClientAuthenticator() {
            @Override
            public void authenticate(AuthRequest request) {
                // Intentionally nothing.
            }

            @Override
            public String describe() {
                return "none";
            }
        };
    }
}
