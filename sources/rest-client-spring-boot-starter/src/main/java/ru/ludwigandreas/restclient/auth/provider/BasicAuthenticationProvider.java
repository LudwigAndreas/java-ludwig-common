package ru.ludwigandreas.restclient.auth.provider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.spi.AuthRequest;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;

/**
 * HTTP Basic.
 *
 * <p>The header is computed once, at startup, rather than per request: it is a pure function of two
 * properties that cannot change without a restart, and base64-encoding a credential on every call is
 * work done for no reason on the hot path.
 *
 * <p>Note what the encoded value is not: it is not encryption, and anyone who can read the header can
 * read the password. Basic is therefore only acceptable over TLS, which the startup validator checks
 * by refusing a {@code http://} base URL with credentials attached.
 */
public class BasicAuthenticationProvider implements ClientAuthenticationProvider {

    @Override
    public String type() {
        return AuthTypes.BASIC;
    }

    @Override
    public Class<? extends ClientAuthProperties> propertiesType() {
        return AuthProperties.class;
    }

    @Override
    public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        AuthProperties auth = (AuthProperties) properties;
        if (auth.getUsername() == null || auth.getUsername().isBlank()) {
            throw new IllegalStateException("Client '" + clientName
                    + "': auth.type=basic requires auth.username.");
        }
        String password = auth.getPassword() == null ? "" : auth.getPassword();
        String header = "Basic " + Base64.getEncoder().encodeToString(
                (auth.getUsername() + ":" + password).getBytes(StandardCharsets.UTF_8));
        String username = auth.getUsername();
        return new ClientAuthenticator() {
            @Override
            public void authenticate(AuthRequest request) {
                request.headers().set("Authorization", header);
            }

            @Override
            public String describe() {
                // The username, never the password: it is what distinguishes two basic-auth clients
                // in a startup log and it is not a secret.
                return "basic(user=" + username + ")";
            }
        };
    }
}
