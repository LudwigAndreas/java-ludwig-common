package ru.ludwigandreas.idempotency.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A response that keeps a copy of what was written to it, so the filter can store it for replay.
 *
 * <h2>Why not {@code ContentCachingResponseWrapper}</h2>
 *
 * <p>Spring's wrapper withholds the body from the client until {@code copyBodyToResponse()} is called,
 * which is the behaviour a caching filter wants and the opposite of what this one wants: the original
 * caller must get its response at the normal time, with no added latency and no dependency on this
 * module remembering to flush. So this wrapper is a tee - every byte goes to the real response
 * <em>and</em> into a buffer - rather than a dam.
 *
 * <p>The buffer is bounded. A response larger than the limit stops being captured, and
 * {@link #truncated()} says so, at which point the filter completes the claim with no stored response
 * rather than one it cannot replay faithfully. Replaying a truncated body would be worse than replaying
 * nothing: the caller would receive a valid-looking response that is missing its end.
 */
class CapturingResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final int limit;

    private ServletOutputStream stream;
    private PrintWriter writer;
    private boolean truncated;

    /**
     * Wraps {@code response}.
     *
     * @param response the real response
     * @param limit    the most bytes to keep; past it, capture stops and the response is marked truncated
     */
    CapturingResponse(HttpServletResponse response, int limit) {
        super(response);
        this.limit = limit;
    }

    /** The bytes written so far. */
    byte[] captured() {
        return captured.toByteArray();
    }

    /** Whether the response outgrew the capture limit and must not be replayed. */
    boolean truncated() {
        return truncated;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called on this response");
        }
        if (stream == null) {
            stream = new TeeStream(super.getOutputStream());
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (stream != null) {
            throw new IllegalStateException("getOutputStream() has already been called on this response");
        }
        if (writer == null) {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            writer = new PrintWriter(new java.io.OutputStreamWriter(
                    new TeeStream(super.getOutputStream()), charset), true);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    /** Copies everything it writes into the buffer, up to the limit. */
    private final class TeeStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        private TeeStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }

        @Override
        public void write(int byteValue) throws IOException {
            delegate.write(byteValue);
            capture(1);
            if (!truncated) {
                captured.write(byteValue);
            }
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            delegate.write(source, offset, length);
            capture(length);
            if (!truncated) {
                captured.write(source, offset, length);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /**
         * Marks the response untruncatable-from once it would outgrow the limit.
         *
         * <p>Checked before the copy rather than after, and latching rather than resetting: once a
         * response has exceeded the limit, the buffer holds a prefix and no later write can make it whole
         * again. Continuing to append would produce a body that is the first N bytes plus a gap plus the
         * tail, which is the one outcome worse than no stored response at all.
         */
        private void capture(int length) {
            if (!truncated && captured.size() + length > limit) {
                truncated = true;
            }
        }
    }
}
