package ru.ludwigandreas.restclient.error;

import lombok.Getter;

/**
 * This service could not obtain, or could not use, the credential for a dependency.
 *
 * <p>Thrown for a token endpoint that refused, a {@code TokenSupplier} that returned nothing, a
 * missing inbound token on a relay client, and a 401 that survived a forced refresh. It exists as a
 * distinct type because all of those are <em>this</em> service's problem, and none of them is the
 * dependency being unhealthy - reporting them as a generic 401, which is what a thin client does,
 * sends the on-call engineer to read the partner's status page instead of the service's own secret
 * configuration.
 *
 * <p>The message never contains the credential, the token, or any part of either.
 */
@Getter
public class RestClientAuthenticationException extends RestClientException {

    private static final long serialVersionUID = 1L;

    /** The {@code auth.type} that failed, e.g. {@code oauth2-client-credentials}. */
    private final String authType;

    /** Whether a refresh was attempted and still did not yield a usable credential. */
    private final boolean afterRefresh;
    /** Creates the exception; {@code message} must never contain any part of a credential. */
    public RestClientAuthenticationException(String clientName, String correlationId, String message,
                                             String authType, boolean afterRefresh, Throwable cause) {
        super(clientName, correlationId, message, cause);
        this.authType = authType;
        this.afterRefresh = afterRefresh;
    }
}
