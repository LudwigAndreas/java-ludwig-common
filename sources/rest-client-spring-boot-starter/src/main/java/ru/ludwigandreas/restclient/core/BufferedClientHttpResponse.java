package ru.ludwigandreas.restclient.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A response whose body has been read into memory, so it can be looked at and still be handed on.
 *
 * <h2>When the pipeline buffers, and when it refuses to</h2>
 *
 * <p>Buffering is the only way to log a body, put a snippet on an exception, or let an error
 * translator parse a problem document - and it is also the way to break a 400MB file download. The
 * pipeline therefore buffers only when it knows it is safe: the response failed, or the client asked
 * for body logging, <em>and</em> the declared {@code Content-Length} is below
 * {@link #MAX_BUFFERED_BYTES}. A response with no {@code Content-Length} - which is what a streaming
 * response looks like - is never buffered.
 *
 * <p>The consequence is stated rather than hidden: a client streaming large responses gets no body
 * in its logs and no body snippet on its exceptions. That is the correct trade, and the alternative
 * is a starter that turns a working download into an {@code OutOfMemoryError} the first time
 * somebody enables body logging.
 */
public final class BufferedClientHttpResponse implements ClientHttpResponse {

    /**
     * The ceiling on what will be read into memory: 256 KiB.
     *
     * <p>Generous for an error document - the largest realistic problem+json is a validation failure
     * listing a few hundred violations - and small enough that a few hundred concurrent calls
     * hitting it at once cannot exhaust a normal heap.
     */
    public static final int MAX_BUFFERED_BYTES = 256 * 1024;

    private final ClientHttpResponse delegate;
    private final byte[] body;

    private BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
        this.delegate = delegate;
        this.body = body;
    }

    /**
     * Reads {@code response}'s body into memory if that is safe, else returns it untouched.
     *
     * @return a buffered response, or the original when buffering was declined
     */
    public static ClientHttpResponse buffer(ClientHttpResponse response) throws IOException {
        long declared = response.getHeaders().getContentLength();
        if (declared < 0 || declared > MAX_BUFFERED_BYTES) {
            return response;
        }
        try (InputStream in = response.getBody()) {
            return new BufferedClientHttpResponse(response, in.readAllBytes());
        }
    }

    /** Whether {@code response} has already been buffered by this class. */
    public static boolean isBuffered(ClientHttpResponse response) {
        return response instanceof BufferedClientHttpResponse;
    }

    /**
     * The buffered body as text, or {@code null} when the response was not buffered.
     *
     * <p>Decoded with the charset the peer declared, falling back to UTF-8. Getting this wrong turns
     * a readable error message into mojibake in the one log line somebody is reading to find out
     * what went wrong.
     */
    public static String bodyAsText(ClientHttpResponse response) {
        if (!(response instanceof BufferedClientHttpResponse buffered)) {
            return null;
        }
        MediaType contentType = buffered.getHeaders().getContentType();
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset() : StandardCharsets.UTF_8;
        return new String(buffered.body, charset);
    }

    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(body);
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
