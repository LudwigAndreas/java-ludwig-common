package ru.ludwigandreas.jira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.jira.auth.JiraCredentials;
import ru.ludwigandreas.jira.error.ErrorCollection;
import ru.ludwigandreas.jira.error.JiraApiException;
import ru.ludwigandreas.jira.error.JiraAuthenticationException;
import ru.ludwigandreas.jira.error.JiraAuthorizationException;
import ru.ludwigandreas.jira.error.JiraConflictException;
import ru.ludwigandreas.jira.error.JiraNotFoundException;
import ru.ludwigandreas.jira.error.JiraRateLimitException;
import ru.ludwigandreas.jira.error.JiraTransportException;
import ru.ludwigandreas.jira.http.HttpMethod;
import ru.ludwigandreas.jira.http.JiraClientMetrics;
import ru.ludwigandreas.jira.http.JiraInterceptor;
import ru.ludwigandreas.jira.http.JiraRequest;
import ru.ludwigandreas.jira.http.JiraResponse;
import ru.ludwigandreas.jira.http.JiraTransport;
import ru.ludwigandreas.jira.http.QueryParams;
import ru.ludwigandreas.jira.http.RequestBody;
import ru.ludwigandreas.jira.json.JiraJson;

/**
 * The layer between the typed API classes and raw HTTP: URI resolution, authentication, the interceptor
 * chain, retries, error mapping and deserialization.
 *
 * <p>Public on purpose, and reachable as {@link JiraClient#rest()}. Jira Server exposes far more endpoints
 * than any client library models - every marketplace app adds its own namespace under {@code /rest} - and a
 * library that models a subset and offers no way past it forces its users to build a second HTTP client
 * beside it, with a second copy of the authentication, retry and error handling. {@link #get(String)} and
 * its siblings are that way past it, with all of this layer's behaviour intact:
 *
 * <pre>{@code
 * JsonNode boards = client.rest()
 *         .get("/rest/agile/1.0/board")
 *         .query("projectKeyOrId", "OPS")
 *         .operation("agile.board.list")
 *         .asTree();
 * }</pre>
 */
public final class JiraRestClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JiraRestClient.class);

    /**
     * Sent on every request. Jira's XSRF filter rejects a state-changing request that looks like it came
     * from a browser form, and this header is how a REST caller declares it is not one. Without it, file
     * uploads fail with a 403 that says nothing about XSRF.
     */
    private static final String XSRF_HEADER = "X-Atlassian-Token";

    private static final String XSRF_VALUE = "no-check";

    /** The status that means "slow down"; named so the retry and metrics paths agree on it. */
    private static final int RATE_LIMIT_STATUS = 429;

    /** The status this client maps onto an absent value rather than an error. */
    private static final int NOT_FOUND_STATUS = 404;

    private static final int UNAUTHORIZED_STATUS = 401;
    private static final int FORBIDDEN_STATUS = 403;
    private static final int CONFLICT_STATUS = 409;

    private final URI baseUri;
    private final JiraCredentials credentials;
    private final JiraTransport transport;
    private final JiraJson json;
    private final ru.ludwigandreas.jira.http.RetryPolicy retryPolicy;
    private final List<JiraInterceptor> interceptors;
    private final JiraClientMetrics metrics;
    private final Map<String, String> defaultHeaders;

    JiraRestClient(URI baseUri,
                   JiraCredentials credentials,
                   JiraTransport transport,
                   JiraJson json,
                   ru.ludwigandreas.jira.http.RetryPolicy retryPolicy,
                   List<JiraInterceptor> interceptors,
                   JiraClientMetrics metrics,
                   Map<String, String> defaultHeaders) {
        this.baseUri = baseUri;
        this.credentials = credentials;
        this.transport = transport;
        this.json = json;
        this.retryPolicy = retryPolicy;
        this.interceptors = List.copyOf(interceptors);
        this.metrics = metrics;
        this.defaultHeaders = Map.copyOf(defaultHeaders);
    }

    /** The instance's base URI, with a trailing slash. */
    public URI baseUri() {
        return baseUri;
    }

    /** The JSON codec, for callers decoding a raw tree into their own types. */
    public JiraJson json() {
        return json;
    }

    /** Starts a {@code GET}. */
    public RequestSpec get(String path) {
        return new RequestSpec(HttpMethod.GET, path);
    }

    /** Starts a {@code POST}. */
    public RequestSpec post(String path) {
        return new RequestSpec(HttpMethod.POST, path);
    }

    /** Starts a {@code PUT}. */
    public RequestSpec put(String path) {
        return new RequestSpec(HttpMethod.PUT, path);
    }

    /** Starts a {@code DELETE}. */
    public RequestSpec delete(String path) {
        return new RequestSpec(HttpMethod.DELETE, path);
    }

    /** Starts a request with an explicit method. */
    public RequestSpec method(HttpMethod method, String path) {
        return new RequestSpec(method, path);
    }

    @Override
    public void close() {
        transport.close();
    }

    /**
     * Runs one request through the interceptor chain and the retry loop, and returns the raw response
     * whatever its status. Error mapping happens in {@link RequestSpec}, not here, so that an interceptor
     * or a caller wanting to inspect a 404 can.
     */
    private JiraResponse send(JiraRequest request) {
        long startedAt = System.nanoTime();
        int status = -1;
        try {
            JiraResponse response = sendWithRetries(request);
            status = response.status();
            return response;
        } finally {
            metrics.recordCall(request.operation(), request.method(), status,
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private JiraResponse sendWithRetries(JiraRequest request) {
        int attempt = 0;
        while (true) {
            attempt++;
            JiraResponse response;
            try {
                response = runChain(request);
            } catch (JiraTransportException e) {
                if (!retryPolicy.shouldRetryFailure(request, attempt)) {
                    throw e;
                }
                metrics.recordRetry(request.operation(), e.getCause() == null
                        ? e.getClass().getSimpleName()
                        : e.getCause().getClass().getSimpleName());
                sleep(retryPolicy.backoff(attempt, Optional.empty()), request, attempt, e.getMessage());
                continue;
            }
            if (response.status() == RATE_LIMIT_STATUS) {
                metrics.recordRateLimited(request.operation());
            }
            if (!retryPolicy.shouldRetryStatus(request, response.status(), attempt)) {
                return response;
            }
            metrics.recordRetry(request.operation(), String.valueOf(response.status()));
            sleep(retryPolicy.backoff(attempt, response.retryAfter()), request, attempt,
                    "HTTP " + response.status());
        }
    }

    private JiraResponse runChain(JiraRequest request) {
        JiraInterceptor.Chain chain = transport::execute;
        // Built back to front so that the first interceptor in the list is the outermost one, which is the
        // order a reader of the configuration expects.
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            JiraInterceptor interceptor = interceptors.get(i);
            JiraInterceptor.Chain next = chain;
            chain = req -> interceptor.intercept(req, next);
        }
        return chain.proceed(request);
    }

    private void sleep(Duration delay, JiraRequest request, int attempt, String reason) {
        log.warn("Retrying {} {} after {} (attempt {}); waiting {} ms",
                request.method(), request.uri(), reason, attempt, delay.toMillis());
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraTransportException("Interrupted while backing off before a retry of "
                    + request.method() + " " + request.uri(), e);
        }
    }

    private JiraApiException toException(JiraRequest request, JiraResponse response) {
        String body = response.bodyAsString();
        ErrorCollection errors = parseErrors(body);
        String method = request.method().name();
        String uri = request.uri().toString();
        return switch (response.status()) {
            case UNAUTHORIZED_STATUS -> new JiraAuthenticationException(response.status(), method, uri, errors, body);
            case FORBIDDEN_STATUS -> new JiraAuthorizationException(response.status(), method, uri, errors, body);
            case NOT_FOUND_STATUS -> new JiraNotFoundException(response.status(), method, uri, errors, body);
            case CONFLICT_STATUS -> new JiraConflictException(response.status(), method, uri, errors, body);
            case RATE_LIMIT_STATUS -> new JiraRateLimitException(
                    response.status(), method, uri, errors, body, response.retryAfter().orElse(null));
            default -> new JiraApiException(response.status(), method, uri, errors, body);
        };
    }

    private ErrorCollection parseErrors(String body) {
        if (body == null || body.isBlank()) {
            return ErrorCollection.empty();
        }
        try {
            return json.read(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), ErrorCollection.class);
        } catch (RuntimeException notAnErrorCollection) {
            // A reverse proxy's HTML page, or a Jira error shape this version does not use. The raw body
            // is carried on the exception either way, so there is nothing to lose by giving up here.
            log.debug("Jira error body was not an error collection", notAnErrorCollection);
            return ErrorCollection.empty();
        }
    }

    /**
     * A request under construction, and the terminal methods that run it.
     *
     * <p>Every terminal method maps a non-2xx status onto the matching exception, except
     * {@link #asOptional(Class)}, which turns a 404 into an empty optional - the one status whose meaning is
     * routinely "absent" rather than "wrong".
     */
    public final class RequestSpec {

        private final HttpMethod method;
        private final String path;
        private final QueryParams query = QueryParams.of();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private RequestBody body;
        private String operation;
        private Boolean retryable;
        private Duration timeout;

        private RequestSpec(HttpMethod method, String path) {
            this.method = method;
            this.path = path;
        }

        /** Adds a query parameter, ignoring a {@code null} value. */
        public RequestSpec query(String name, Object value) {
            query.add(name, value);
            return this;
        }

        /** Adds one query parameter per element, which is how Jira expects {@code fields} and {@code expand}. */
        public RequestSpec queryEach(String name, java.util.Collection<?> values) {
            query.addEach(name, values);
            return this;
        }

        /** Adds the elements as a single comma-joined parameter. */
        public RequestSpec queryJoined(String name, java.util.Collection<?> values) {
            query.addJoined(name, values);
            return this;
        }

        /** Sets a request header. */
        public RequestSpec header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /** Serializes an object as the JSON request body. */
        public RequestSpec body(Object payload) {
            this.body = RequestBody.json(json.write(payload));
            return this;
        }

        /** Sets an already-rendered body, for multipart uploads. */
        public RequestSpec rawBody(RequestBody payload) {
            this.body = payload;
            return this;
        }

        /** Sets the low-cardinality operation name used for metrics and log lines. */
        public RequestSpec operation(String operation) {
            this.operation = operation;
            return this;
        }

        /**
         * Overrides the method-derived retry decision. Set this on a {@code POST} that is semantically a
         * read - Jira's {@code /search}, Structure's {@code /value} - and on nothing else.
         *
         * @param retryable whether the request may be re-sent
         * @return this spec
         */
        public RequestSpec retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        /** Overrides the client-wide response timeout for this request. */
        public RequestSpec timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Sends the request and returns the raw response, mapping non-2xx onto an exception. */
        public JiraResponse execute() {
            JiraRequest request = build();
            JiraResponse response = send(request);
            if (!response.isSuccessful()) {
                throw toException(request, response);
            }
            return response;
        }

        /** Sends the request and discards the body, for updates and deletes. */
        public void asVoid() {
            execute();
        }

        /** Sends the request and parses the body as the given type. */
        public <T> T as(Class<T> type) {
            return json.read(execute().body(), type);
        }

        /** Sends the request and parses the body as the given generic type. */
        public <T> T as(TypeReference<T> type) {
            return json.read(execute().body(), type);
        }

        /** Sends the request and parses the body as the given resolved type. */
        public <T> T as(JavaType type) {
            return json.read(execute().body(), type);
        }

        /** Sends the request and parses the body as a raw JSON tree. */
        public JsonNode asTree() {
            return json.readTree(execute().body());
        }

        /**
         * Sends the request, parsing the body on success and answering empty on a 404.
         *
         * <p>Only 404 is swallowed. A 403 still throws, because "you may not see this" and "this does not
         * exist" call for different handling even though Jira sometimes conflates them - see
         * {@link JiraNotFoundException}.
         *
         * @param type the target type
         * @param <T> the target type
         * @return the parsed body, or empty when Jira answered 404
         */
        public <T> Optional<T> asOptional(Class<T> type) {
            JiraRequest request = build();
            JiraResponse response = send(request);
            if (response.status() == NOT_FOUND_STATUS) {
                return Optional.empty();
            }
            if (!response.isSuccessful()) {
                throw toException(request, response);
            }
            return response.hasBody()
                    ? Optional.of(json.read(response.body(), type))
                    : Optional.empty();
        }

        private JiraRequest build() {
            String relative = path.startsWith("/") ? path.substring(1) : path;
            String suffix = query.isEmpty() ? "" : "?" + query.render();
            URI uri = baseUri.resolve(relative + suffix);
            JiraRequest.Builder builder = JiraRequest.builder(method, uri)
                    .operation(operation == null ? method.name().toLowerCase(java.util.Locale.ROOT) : operation)
                    .header("Accept", "application/json")
                    .header(XSRF_HEADER, XSRF_VALUE);
            defaultHeaders.forEach(builder::header);
            headers.forEach(builder::header);
            if (body != null) {
                builder.body(body);
            }
            if (retryable != null) {
                builder.retryable(retryable);
            }
            if (timeout != null) {
                builder.timeout(timeout);
            }
            credentials.apply(builder);
            JiraRequest request = builder.build();
            if (log.isDebugEnabled()) {
                log.debug("Jira {} {} (operation={}, retryable={})",
                        request.method(), request.uri(), request.operation(), request.isRetryable());
            }
            return request;
        }
    }
}
