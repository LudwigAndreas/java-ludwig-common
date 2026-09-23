package ru.ludwigandreas.restclient.core;

import java.io.IOException;
import java.io.InputStream;
import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A failed response carrying the two things the error handler cannot otherwise recover: the URI
 * template, and the already-redacted body snippet.
 *
 * <p>Both are known inside the pipeline and lost by the time Spring applies a status handler - the
 * observation scope that held the template has closed, and the body has either been consumed or
 * deliberately not read. Carrying them on the response is what lets a typed exception say
 * {@code GET /invoices/{id} returned 503} instead of quoting an expanded path with a customer id in
 * it.
 *
 * <p>Only failures are wrapped. A successful response is handed on untouched, so the normal path
 * costs nothing.
 */
@Getter
public class AnnotatedClientHttpResponse implements ClientHttpResponse {

    private final ClientHttpResponse delegate;

    /** The URI template of the call, e.g. {@code /invoices/{id}}. */
    private final String uriTemplate;

    /** The redacted, truncated body, or {@code null} when the body was not safe to read. */
    private final String bodySnippet;
    /** Wraps {@code delegate}, carrying the template and snippet the error handler needs. */
    public AnnotatedClientHttpResponse(ClientHttpResponse delegate, String uriTemplate,
                                       String bodySnippet) {
        this.delegate = delegate;
        this.uriTemplate = uriTemplate;
        this.bodySnippet = bodySnippet;
    }

    @Override
    public InputStream getBody() throws IOException {
        return delegate.getBody();
    }

    @Override
    public HttpHeaders getHeaders() {
        return delegate.getHeaders();
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return delegate.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
        return delegate.getStatusText();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
