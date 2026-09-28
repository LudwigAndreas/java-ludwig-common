package ru.ludwigandreas.restclient.auth;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import ru.ludwigandreas.restclient.spi.AuthRequest;

/**
 * The pipeline's implementation of {@link AuthRequest}.
 *
 * <p>A plain mutable holder, created once per attempt. It exists as a separate type from the
 * pipeline's own request state so that an authenticator - which is service-supplied code - is handed
 * something with exactly two things it can change, instead of the live request object with its body,
 * its execution callback and its retry bookkeeping.
 */
@Getter
public class MutableAuthRequest implements AuthRequest {

    private final String clientName;
    private final HttpMethod method;
    private final HttpHeaders headers;
    private final int attempt;
    private final boolean credentialRejected;

    @Setter
    private URI target;

    /** Creates the request view handed to an authenticator for one attempt. */
    // CHECKSTYLE.OFF: ParameterNumber - five values, all of them immutable state of one attempt.
    public MutableAuthRequest(String clientName, HttpMethod method, URI target, HttpHeaders headers,
                              int attempt, boolean credentialRejected) {
        this.clientName = clientName;
        this.method = method;
        this.target = target;
        this.headers = headers;
        this.attempt = attempt;
        this.credentialRejected = credentialRejected;
    }
    // CHECKSTYLE.ON: ParameterNumber

    @Override
    public String clientName() {
        return clientName;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public URI uri() {
        return target;
    }

    @Override
    public void uri(URI uri) {
        this.target = uri;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public int attempt() {
        return attempt;
    }

    @Override
    public boolean credentialRejected() {
        return credentialRejected;
    }
}
