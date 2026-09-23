package ru.ludwigandreas.restclient.spi;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * The outgoing request as an authenticator may still change it.
 *
 * <p>Headers and URI, and nothing else. An authenticator that could rewrite the body or the method
 * would be an interceptor wearing the wrong name, and the pipeline could no longer promise that an
 * authentication failure is attributable to authentication.
 *
 * <p>The URI is mutable because an API key is legitimately a query parameter for some partners -
 * a bad design, universally, and one this starter has to speak anyway.
 */
public interface AuthRequest {

    /** Name of the client this request belongs to. */
    String clientName();

    /** The request method, for an authenticator that signs it. */
    HttpMethod method();

    /** The current target URI. */
    URI uri();

    /** Replaces the target URI. */
    void uri(URI uri);

    /** Mutable headers of the outgoing request. */
    HttpHeaders headers();

    /**
     * Which attempt this is, counting from 1.
     *
     * <p>An authenticator that caches a token uses this to tell a first call from a retry: attempt
     * 2 after a 401 is the case where the cached token must be discarded rather than reused.
     */
    int attempt();

    /**
     * Whether the previous attempt was rejected with 401 or 403.
     *
     * <p>True is the signal to force a refresh instead of serving the cached credential, and it is
     * the only supported way for an authenticator to learn that - reading the previous response
     * would make every authenticator a response handler too.
     */
    boolean credentialRejected();
}
