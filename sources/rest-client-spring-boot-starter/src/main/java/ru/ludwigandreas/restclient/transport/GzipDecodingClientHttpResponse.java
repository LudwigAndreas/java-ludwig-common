package ru.ludwigandreas.restclient.transport;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Decodes a {@code Content-Encoding: gzip} or {@code deflate} body for engines that will not.
 *
 * <p>Only the JDK transport needs it - Apache and Reactor Netty decode transparently - but
 * {@code compression: true} has to mean the same thing on all three, or the engine choice stops
 * being an operational detail and becomes a behavioural one.
 *
 * <p>The decoding is lazy: the stream is wrapped, not read. A response the caller never consumes
 * costs nothing, and a streaming download stays a stream.
 *
 * <p>{@code Content-Length} and {@code Content-Encoding} are removed from the headers the caller
 * sees, because both are now lies - the length describes the compressed bytes, and the encoding
 * describes an encoding that has already been undone. Leaving them is how a message converter reads
 * exactly {@code Content-Length} bytes of a longer decompressed stream and returns truncated JSON.
 */
public class GzipDecodingClientHttpResponse implements ClientHttpResponse {

    private final ClientHttpResponse delegate;
    private final String encoding;
    private final HttpHeaders headers;
    /** Wraps {@code delegate}, undoing {@code encoding} lazily when the body is read. */
    public GzipDecodingClientHttpResponse(ClientHttpResponse delegate, String encoding) {
        this.delegate = delegate;
        this.encoding = encoding;
        HttpHeaders copy = new HttpHeaders();
        copy.addAll(delegate.getHeaders());
        copy.remove(HttpHeaders.CONTENT_ENCODING);
        copy.remove(HttpHeaders.CONTENT_LENGTH);
        this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
    }

    /** Whether {@code response} is encoded with something this class can undo. */
    public static String decodableEncoding(ClientHttpResponse response) {
        String value = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "gzip".equals(normalized) || "x-gzip".equals(normalized) || "deflate".equals(normalized)
                ? normalized : null;
    }

    @Override
    public InputStream getBody() throws IOException {
        InputStream body = delegate.getBody();
        return "deflate".equals(encoding) ? new InflaterInputStream(body) : new GZIPInputStream(body);
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
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
