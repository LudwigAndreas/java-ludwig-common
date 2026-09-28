package ru.ludwigandreas.restclient.auth.provider;

import java.net.URI;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.spi.AuthRequest;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;

/**
 * A static API key, in a header or - when the partner insists - a query parameter.
 *
 * <p>The query-parameter form is supported and disliked in equal measure. A credential in the URI is
 * written to the access log of every proxy on the path, appears in {@code Referer} headers, and is
 * the reason the startup validator warns once per client that uses it. It exists because refusing it
 * would not make those partners disappear; it would make this starter unusable for them.
 */
public class ApiKeyAuthenticationProvider implements ClientAuthenticationProvider {

    @Override
    public String type() {
        return AuthTypes.API_KEY;
    }

    @Override
    public Class<? extends ClientAuthProperties> propertiesType() {
        return AuthProperties.class;
    }

    @Override
    public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        AuthProperties auth = (AuthProperties) properties;
        if (auth.getKey() == null || auth.getKey().isBlank()) {
            throw new IllegalStateException("Client '" + clientName
                    + "': auth.type=api-key requires auth.key.");
        }
        String value = (auth.getValuePrefix() == null ? "" : auth.getValuePrefix()) + auth.getKey();
        String queryParam = auth.getQueryParamName();
        String header = auth.getHeaderName();
        return new ClientAuthenticator() {
            @Override
            public void authenticate(AuthRequest request) {
                if (queryParam != null && !queryParam.isBlank()) {
                    URI rewritten = UriComponentsBuilder.fromUri(request.uri())
                            .replaceQueryParam(queryParam, value)
                            .build(true)
                            .toUri();
                    request.uri(rewritten);
                    return;
                }
                request.headers().set(header, value);
            }

            @Override
            public String describe() {
                return queryParam != null && !queryParam.isBlank()
                        ? "api-key(query=" + queryParam + ")"
                        : "api-key(header=" + header + ")";
            }
        };
    }
}
