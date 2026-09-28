package ru.ludwigandreas.idempotency.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be read twice: once to fingerprint it, once by the handler.
 *
 * <h2>Why this is necessary and why it is bounded</h2>
 *
 * <p>A servlet request body is a stream and is consumed once. Fingerprinting it means reading it, which
 * would leave nothing for the controller - so the bytes are buffered and handed out again.
 * {@code ContentCachingRequestWrapper} does not help here: it caches what the <em>downstream</em> reads,
 * so the cache is only populated after the handler has already had the stream, which is too late for a
 * decision that has to be made before the handler runs.
 *
 * <p>Buffering a request body in memory is exactly the mistake {@code file-ingest} exists to avoid, so
 * the limit is not optional: a body longer than the configured maximum is not buffered and not
 * fingerprinted, and the filter says so - see {@code IdempotencyProperties.Http#getMaxFingerprintedBody()}.
 * An endpoint receiving bodies large enough to matter is not one where this filter should be reading them.
 */
class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    /**
     * Wraps {@code request}, having already read its body.
     *
     * @param request the original request
     * @param body    its body, read in full by the caller
     */
    CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** The buffered body. Not copied: it is read by this class and by the fingerprinter, never written. */
    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffered = new ByteArrayInputStream(body);
        return new ServletInputStream() {

            @Override
            public boolean isFinished() {
                return buffered.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // Asynchronous reads are meaningless against a buffer that is already in memory: there is
                // no moment at which data "becomes available", so a listener would either never fire or
                // have to be called inline, and both are worse than saying the mode is not supported.
                throw new UnsupportedOperationException(
                        "A cached request body is always ready; a ReadListener would never fire");
            }

            @Override
            public int read() {
                return buffered.read();
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                return buffered.read(target, offset, length);
            }

            @Override
            public int available() {
                return buffered.available();
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        Charset charset = getCharacterEncoding() == null
                ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
