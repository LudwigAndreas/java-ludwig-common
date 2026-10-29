package ru.ludwigandreas.security.admin;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds what an approved request's handler answered - status, headers, body - without any of it reaching the
 * approver's own response, which carries the approval instead.
 */
final class CapturedResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private int status = SC_OK;
    private String contentType;
    private String characterEncoding = StandardCharsets.UTF_8.name();
    private PrintWriter writer;
    private boolean committed;

    CapturedResponse(HttpServletResponse approver) {
        super(approver);
    }

    AdminApproval.Outcome outcome() {
        if (writer != null) {
            writer.flush();
        }
        return new AdminApproval.Outcome(status, contentType, body.toByteArray());
    }

    @Override
    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void sendError(int status) {
        sendError(status, null);
    }

    @Override
    public void sendError(int status, String message) {
        this.status = status;
        this.committed = true;
    }

    @Override
    public void sendRedirect(String location) {
        this.status = SC_FOUND;
        setHeader("Location", location);
        this.committed = true;
    }

    private String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    @Override
    public void setHeader(String name, String value) {
        headers.put(key(name), new ArrayList<>(List.of(value)));
    }

    @Override
    public void addHeader(String name, String value) {
        headers.computeIfAbsent(key(name), each -> new ArrayList<>()).add(value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, String.valueOf(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, String.valueOf(value));
    }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, String.valueOf(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, String.valueOf(date));
    }

    @Override
    public boolean containsHeader(String name) {
        return headers.containsKey(key(name));
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(key(name));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return headers.getOrDefault(key(name), List.of());
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }

    @Override
    public void addCookie(Cookie cookie) {
        // An approved execution sets no cookie on the approver.
    }

    @Override
    public void setContentType(String type) {
        this.contentType = type;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        this.characterEncoding = charset;
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setContentLength(int length) {
        // The captured body is measured, not declared.
    }

    @Override
    public void setContentLengthLong(long length) {
        // The captured body is measured, not declared.
    }

    @Override
    public void setLocale(Locale locale) {
        // The approver's response keeps its own locale.
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            @Override
            public void write(int value) {
                body.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                body.write(bytes, offset, length);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                throw new UnsupportedOperationException("An approved request is replayed synchronously");
            }
        };
    }

    @Override
    public PrintWriter getWriter() {
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(body, Charset.forName(characterEncoding)), true);
        }
        return writer;
    }

    @Override
    public void flushBuffer() {
        committed = true;
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void reset() {
        resetBuffer();
        headers.clear();
        status = SC_OK;
        contentType = null;
    }

    @Override
    public void resetBuffer() {
        body.reset();
    }

    @Override
    public void setBufferSize(int size) {
        // Unbounded until captured; the outcome is capped when kept.
    }
}
