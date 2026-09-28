package ru.ludwigandreas.jira.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One fully resolved HTTP request: absolute URI, headers, and an optional rendered body.
 *
 * <p>Immutable, and produced only by {@link Builder}. Interceptors receive one and may return a modified
 * copy through {@link #toBuilder()}; nothing mutates a request in place, so a retry re-sends exactly the
 * bytes that were sent the first time rather than a request that a stateful interceptor has touched twice.
 */
public final class JiraRequest {

    private final HttpMethod method;
    private final URI uri;
    private final Map<String, String> headers;
    private final RequestBody body;
    private final Duration timeout;
    private final boolean retryable;
    private final String operation;

    private JiraRequest(Builder builder) {
        this.method = builder.method;
        this.uri = builder.uri;
        this.headers = Map.copyOf(builder.headers);
        this.body = builder.body;
        this.timeout = builder.timeout;
        this.retryable = builder.retryable == null ? builder.method.isIdempotent() : builder.retryable;
        this.operation = builder.operation;
    }

    /** Starts a request for the given method and absolute URI. */
    public static Builder builder(HttpMethod method, URI uri) {
        return new Builder(method, uri);
    }

    /** A builder pre-populated with this request's state, for interceptors that add or replace a header. */
    public Builder toBuilder() {
        Builder builder = new Builder(method, uri);
        builder.headers.putAll(headers);
        builder.body = body;
        builder.timeout = timeout;
        builder.retryable = retryable;
        builder.operation = operation;
        return builder;
    }

    /** HTTP method. */
    public HttpMethod method() {
        return method;
    }

    /** Absolute request URI including the query string. */
    public URI uri() {
        return uri;
    }

    /** Request headers, in insertion order. */
    public Map<String, String> headers() {
        return headers;
    }

    /** The rendered body, absent for methods that carry none. */
    public Optional<RequestBody> body() {
        return Optional.ofNullable(body);
    }

    /** Per-request timeout override, absent when the client-wide response timeout applies. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Whether the retry policy may re-send this request. Defaults to {@link HttpMethod#isIdempotent()} and
     * is overridden only where an endpoint is safe to repeat despite using {@code POST} - Jira's
     * {@code /search}, which is a query expressed as a POST because a JQL string does not fit in a URL.
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Stable low-cardinality name of the operation ({@code issue.get}, {@code search}), used as a metric
     * tag and a log field. Never the URI: tagging a timer with {@code /issue/ABC-123} produces one time
     * series per issue and takes the metrics backend down.
     */
    public String operation() {
        return operation;
    }

    @Override
    public String toString() {
        return method + " " + uri;
    }

    /** Fluent builder for {@link JiraRequest}. */
    public static final class Builder {

        private final HttpMethod method;
        private final URI uri;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private RequestBody body;
        private Duration timeout;
        private Boolean retryable;
        private String operation = "unknown";

        private Builder(HttpMethod method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        /** Sets a header, replacing any previous value for the same name. */
        public Builder header(String name, String value) {
            if (value != null) {
                headers.put(name, value);
            }
            return this;
        }

        /** Sets the rendered body. */
        public Builder body(RequestBody body) {
            this.body = body;
            return this;
        }

        /** Overrides the client-wide response timeout for this one request. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Overrides the method-derived retry decision. */
        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        /** Sets the low-cardinality operation name used for metrics and logging. */
        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }

        /** Builds the immutable request. */
        public JiraRequest build() {
            return new JiraRequest(this);
        }
    }
}
