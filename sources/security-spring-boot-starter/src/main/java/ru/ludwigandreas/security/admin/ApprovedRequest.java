package ru.ludwigandreas.security.admin;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * A stored administrative request, replayed over the approver's request: the stored method, path, query, body and
 * the allow-listed headers, and attributes of its own so nothing from the approve call leaks into the dispatch.
 */
final class ApprovedRequest extends HttpServletRequestWrapper {

    /** The request attribute naming the approval being executed. */
    static final String EXECUTING = ApprovedRequest.class.getName() + ".EXECUTING";

    private final AdminApproval.StoredRequest stored;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final Map<String, String[]> parameters;
    private final ByteArrayInputStream body;

    ApprovedRequest(HttpServletRequest approver, AdminApproval approval) {
        super(approver);
        this.stored = approval.request();
        this.body = new ByteArrayInputStream(stored.body());
        attributes.put(EXECUTING, approval.id());
        header(HttpHeaders.CONTENT_TYPE, stored.contentType());
        header(HttpHeaders.ACCEPT_LANGUAGE, stored.acceptLanguage());
        header(AdminInvocation.REASON_HEADER, stored.reason());
        header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        this.parameters = parse(stored.query());
    }

    private void header(String name, String value) {
        if (value != null) {
            headers.put(name.toLowerCase(Locale.ROOT), List.of(value));
        }
    }

    private static Map<String, String[]> parse(String query) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                values.computeIfAbsent(name, key -> new java.util.ArrayList<>()).add(value);
            }
        }
        Map<String, String[]> parsed = new LinkedHashMap<>();
        values.forEach((name, list) -> parsed.put(name, list.toArray(String[]::new)));
        return Collections.unmodifiableMap(parsed);
    }

    @Override
    public String getMethod() {
        return stored.method();
    }

    @Override
    public String getRequestURI() {
        return getContextPath() + stored.path();
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(getScheme()).append("://").append(getServerName()).append(':').append(getServerPort())
                .append(getRequestURI());
    }

    @Override
    public String getServletPath() {
        return stored.path();
    }

    @Override
    public String getPathInfo() {
        return null;
    }

    @Override
    public String getQueryString() {
        return stored.query();
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameters.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters.keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        return parameters.get(name);
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null ? null : values.get(0);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(headers.keySet());
    }

    @Override
    public int getIntHeader(String name) {
        String value = getHeader(name);
        return value == null ? -1 : Integer.parseInt(value);
    }

    @Override
    public long getDateHeader(String name) {
        return -1;
    }

    @Override
    public String getContentType() {
        return stored.contentType();
    }

    @Override
    public int getContentLength() {
        return stored.body().length;
    }

    @Override
    public long getContentLengthLong() {
        return stored.body().length;
    }

    @Override
    public String getCharacterEncoding() {
        if (stored.contentType() == null) {
            return null;
        }
        Charset charset = MediaType.parseMediaType(stored.contentType()).getCharset();
        return charset == null ? null : charset.name();
    }

    @Override
    public Locale getLocale() {
        if (stored.acceptLanguage() == null) {
            return super.getLocale();
        }
        List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(stored.acceptLanguage());
        return ranges.isEmpty() ? super.getLocale() : Locale.forLanguageTag(ranges.get(0).getRange());
    }

    @Override
    public ServletInputStream getInputStream() {
        return new ServletInputStream() {
            @Override
            public int read() {
                return body.read();
            }

            @Override
            public boolean isFinished() {
                return body.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("An approved request is replayed synchronously");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        return new BufferedReader(new InputStreamReader(getInputStream(),
                encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }
}
