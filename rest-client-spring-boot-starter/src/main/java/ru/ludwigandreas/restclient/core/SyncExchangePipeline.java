package ru.ludwigandreas.restclient.core;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import ru.ludwigandreas.restclient.auth.MutableAuthRequest;
import ru.ludwigandreas.restclient.config.LogDetail;
import ru.ludwigandreas.restclient.error.RestClientAuthenticationException;
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;
import ru.ludwigandreas.restclient.error.RestClientConnectionException;
import ru.ludwigandreas.restclient.error.RestClientTimeoutException;
import ru.ludwigandreas.restclient.observability.audit.AuditRecorder;
import ru.ludwigandreas.restclient.resilience.AttemptOutcome;
import ru.ludwigandreas.restclient.resilience.RetryAfter;
import ru.ludwigandreas.restclient.resilience.TransportFailures;
import ru.ludwigandreas.restclient.transport.GzipDecodingClientHttpResponse;

/**
 * The blocking execution pipeline: one logical call, however many attempts it takes.
 *
 * <h2>Where it sits, and why there</h2>
 *
 * <p>It is a {@code ClientHttpRequestFactory} wrapper, which puts it <em>below</em> Spring's
 * interceptor chain and <em>inside</em> the observation scope. Both placements are load-bearing.
 *
 * <p>Below the interceptors, because Spring's {@code InterceptingClientHttpRequest} advances a
 * shared iterator as it walks the chain: an interceptor that calls {@code execution.execute()} twice
 * does not re-run the interceptors after it, so a retry implemented as an interceptor would silently
 * skip authentication and logging on every attempt but the first. Here, retries re-execute the
 * request against the transport directly and every attempt gets the same treatment.
 *
 * <p>Inside the observation scope, because the scope is the only place the URI <em>template</em>
 * still exists - see {@link UriTemplates}.
 *
 * <h2>The order</h2>
 *
 * <pre>
 *   RateLimiter -> Bulkhead -> CircuitBreaker -> Retry -> [authenticate -> send]
 * </pre>
 *
 * <p>identical to the reactive pipeline's. Authentication is inside the retry, which is what makes
 * 401 -> refresh -> retry possible at all: each attempt asks the authenticator again, and the
 * attempt after a 401 asks with {@code credentialRejected} set.
 */
public class SyncExchangePipeline {

    private static final int UNAUTHORIZED = 401;

    /** The lowest status code that is an error, and therefore one whose body is always buffered. */
    private static final int FIRST_ERROR_STATUS = 400;

    private final ClientRuntime runtime;
    private final ClientHttpRequestFactory transport;
    private final boolean decodeGzip;

    /**
     * Creates the pipeline for one named client over one transport.
     *
     * @param decodeGzip whether the transport leaves {@code Content-Encoding} for us to undo, which
     *                   is true only for the JDK engine
     */
    public SyncExchangePipeline(ClientRuntime runtime, ClientHttpRequestFactory transport,
                                boolean decodeGzip) {
        this.runtime = runtime;
        this.transport = transport;
        this.decodeGzip = decodeGzip;
    }

    /** Runs one logical call and returns the response the caller should see. */
    public ClientHttpResponse execute(HttpMethod method, URI uri, HttpHeaders headers, byte[] body)
            throws IOException {
        // Once per logical call, throttled: this is where a changed log level or retry count takes
        // effect without a restart. The common path is one volatile read.
        runtime.refreshIfStale();
        RequestOverrides overrides = RestClientHeaders.consume(headers);
        String uriTemplate = UriTemplates.resolve(runtime.getObservationRegistry(), uri);
        CallState state = new CallState(method, uri, uriTemplate, headers, body, overrides);
        long startNanos = System.nanoTime();
        try {
            ClientHttpResponse response = withRateLimiter(() -> withBulkhead(() -> withBreaker(state)));
            audit(state, response, null, Duration.ofNanos(System.nanoTime() - startNanos));
            return response;
        } catch (RuntimeException ex) {
            audit(state, null, ex, Duration.ofNanos(System.nanoTime() - startNanos));
            throw ex;
        }
    }

    // ---------------------------------------------------------------- decorators, outermost first

    private ClientHttpResponse withRateLimiter(Supplier<ClientHttpResponse> next) {
        RateLimiter limiter = runtime.getResilience().getRateLimiter();
        if (limiter == null) {
            return next.get();
        }
        try {
            RateLimiter.waitForPermission(limiter);
        } catch (RequestNotPermitted ex) {
            throw notPermitted("rate-limiter", ex);
        }
        return next.get();
    }

    private ClientHttpResponse withBulkhead(Supplier<ClientHttpResponse> next) {
        Bulkhead semaphore = runtime.getResilience().getBulkhead();
        if (semaphore != null) {
            try {
                semaphore.acquirePermission();
            } catch (BulkheadFullException ex) {
                throw notPermitted("bulkhead", ex);
            }
            try {
                return next.get();
            } finally {
                semaphore.onComplete();
            }
        }
        ThreadPoolBulkhead pool = runtime.getResilience().getThreadPoolBulkhead();
        if (pool == null) {
            return next.get();
        }
        try {
            // The calling thread is released for the duration of the call, which is the entire point
            // of the thread-pool variant; join() is what puts the result back on it.
            return pool.submit(next::get).toCompletableFuture().join();
        } catch (BulkheadFullException ex) {
            throw notPermitted("bulkhead", ex);
        } catch (CompletionException ex) {
            throw unwrapCompletion(ex);
        }
    }

    /**
     * Acquires one permit per logical call and records one result for it.
     *
     * <p>One, not one per attempt: the breaker's window then counts the thing the caller experiences,
     * and its slow-call detection measures the duration the caller actually waited, retries and
     * backoff included. The benefit of having the retry inside is not that the breaker sees more
     * events - it is that an open breaker stops the retries from being made at all, which is what
     * keeps a failing dependency from being hit three times as hard as usual.
     */
    private ClientHttpResponse withBreaker(CallState state) {
        CircuitBreaker breaker = runtime.getResilience().getCircuitBreaker();
        if (breaker == null) {
            return retryLoop(state);
        }
        try {
            breaker.acquirePermission();
        } catch (CallNotPermittedException ex) {
            throw notPermitted("circuit-breaker", ex);
        }
        long start = System.nanoTime();
        try {
            ClientHttpResponse response = retryLoop(state);
            breaker.onResult(System.nanoTime() - start, TimeUnit.NANOSECONDS, state.lastOutcome);
            return response;
        } catch (RuntimeException ex) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, ex);
            throw ex;
        }
    }

    // ------------------------------------------------------------------------------- the retry loop

    private ClientHttpResponse retryLoop(CallState state) {
        int maxAttempts = state.overrides.effectiveMaxAttempts(runtime.getResilience().getRetry().maxAttempts());
        long callStart = System.nanoTime();
        while (true) {
            AttemptResult result = attempt(state);
            state.lastOutcome = result.outcome();
            long elapsed = Duration.ofNanos(System.nanoTime() - callStart).toMillis();

            if (result.response() != null) {
                if (shouldRefreshCredentialAndRetry(state, result)) {
                    continue;
                }
                if (!retryable(state, result.outcome(), maxAttempts, elapsed)) {
                    return result.response();
                }
                // The response is closed before the next attempt: an unclosed response holds its
                // connection, and a retry loop that leaks one per attempt exhausts the pool exactly
                // when the dependency is already struggling.
                close(result.response());
            } else if (!retryable(state, result.outcome(), maxAttempts, elapsed)) {
                throw translateFailure(state, result.outcome().failure());
            }
            waitBeforeNextAttempt(state, result.outcome());
        }
    }

    private boolean retryable(CallState state, AttemptOutcome outcome, int maxAttempts, long elapsed) {
        if (outcome.attempt() >= maxAttempts) {
            return false;
        }
        return runtime.getResilience().getRetry()
                .shouldRetry(outcome, state.method.name(), state.overrides.retry(), elapsed);
    }

    private void waitBeforeNextAttempt(CallState state, AttemptOutcome outcome) {
        long wait = runtime.getResilience().getRetry().waitMillis(outcome);
        String reason = runtime.getResilience().getRetry().reason(outcome);
        runtime.getMeters().retry(runtime.getName(), reason);
        runtime.getListeners().onRetry(state.describe(), outcome.attempt() + 1, wait, reason);
        state.retryAttempt++;
        state.attemptNumber++;
        sleep(wait);
    }

    /**
     * The one extra attempt an expired credential is allowed, outside the retry budget.
     *
     * <p>A 401 on a cached token usually means the token died earlier than its {@code exp} said - a
     * revocation, a rotated signing key, a clock that disagrees. That is not a transport failure and
     * it is not the dependency being unhealthy, so charging it to the retry budget would spend the
     * budget on something that is not what the budget is for. It is granted once per logical call;
     * a second 401 is a real authorization failure and is reported as one.
     */
    private boolean shouldRefreshCredentialAndRetry(CallState state, AttemptResult result) {
        if (state.credentialRetryUsed || result.outcome().statusCode() != UNAUTHORIZED
                || !runtime.getAuthenticator().refreshOnUnauthorized()) {
            return false;
        }
        close(result.response());
        runtime.getAuthenticator().invalidate();
        state.credentialRetryUsed = true;
        state.credentialRejected = true;
        state.attemptNumber++;
        return true;
    }

    // ---------------------------------------------------------------------------------- one attempt

    private AttemptResult attempt(CallState state) {
        long start = System.nanoTime();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(state.headers);
            URI target = authenticate(state, headers);

            ClientHttpRequest request = transport.createRequest(target, state.method);
            request.getHeaders().addAll(headers);
            if (state.body.length > 0) {
                try (OutputStream out = request.getBody()) {
                    out.write(state.body);
                }
            }
            runtime.getExchangeLogger().logRequest(state.describe(), null);
            runtime.getListeners().onRequest(state.describe());

            ClientHttpResponse response = decode(request.execute());
            return observed(state, response, Duration.ofNanos(System.nanoTime() - start));
        } catch (IOException | RuntimeException ex) {
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            if (ex instanceof RestClientAuthenticationException) {
                // Not an attempt outcome: no request was made, and no retry or breaker accounting
                // should treat it as the dependency's failure.
                throw (RestClientAuthenticationException) ex;
            }
            runtime.getExchangeLogger().logFailure(state.describe(), ex, duration);
            runtime.getListeners().onError(state.describe(), ex);
            return new AttemptResult(null,
                    new AttemptOutcome(0, ex, -1, duration, state.retryAttempt));
        }
    }

    private URI authenticate(CallState state, HttpHeaders headers) {
        URI target = RequestDefaults.withQueryParams(state.uri,
                runtime.getProperties().getDefaultQueryParams());
        MutableAuthRequest authRequest = new MutableAuthRequest(runtime.getName(), state.method,
                target, headers, state.attemptNumber, state.credentialRejected);
        try {
            runtime.getAuthenticator().authenticate(authRequest);
        } catch (RuntimeException ex) {
            throw new RestClientAuthenticationException(runtime.getName(),
                    runtime.getCallContext().correlationId(),
                    "could not obtain credentials: " + ex.getMessage(),
                    runtime.getProperties().getAuth().getType(), state.credentialRejected, ex);
        }
        return authRequest.uri();
    }

    private ClientHttpResponse decode(ClientHttpResponse response) {
        if (!decodeGzip) {
            return response;
        }
        String encoding = GzipDecodingClientHttpResponse.decodableEncoding(response);
        return encoding == null ? response : new GzipDecodingClientHttpResponse(response, encoding);
    }

    /** Buffers where it is safe to, then reports the attempt to the logger and the listeners. */
    private AttemptResult observed(CallState state, ClientHttpResponse response, Duration duration)
            throws IOException {
        int status = response.getStatusCode().value();
        boolean failed = status >= FIRST_ERROR_STATUS;
        boolean wantBody = failed || runtime.getExchangeLogger().wantsBody();
        ClientHttpResponse observable = wantBody ? BufferedClientHttpResponse.buffer(response) : response;
        String snippet = bodySnippet(observable);

        Map<String, List<String>> headers = runtime.getRedactor().redact(observable.getHeaders());
        var outbound = new ru.ludwigandreas.restclient.spi.OutboundResponse(
                status, headers, duration, snippet);
        runtime.getExchangeLogger().logResponse(state.describe(), outbound);
        runtime.getListeners().onResponse(state.describe(), outbound);
        state.lastStatus = status;
        state.lastResponseHeaders = headers;

        long retryAfter = RetryAfter.millis(observable.getHeaders().getFirst("Retry-After"),
                runtime.getProperties().getResilience().getRetry().getMaxRetryAfter(), runtime.getClock());
        // Only failures are wrapped: the annotation exists for the error handler, and a successful
        // response should not pay for an extra object or an extra layer of delegation.
        ClientHttpResponse delivered = failed
                ? new AnnotatedClientHttpResponse(observable, state.uriTemplate, snippet)
                : observable;
        return new AttemptResult(delivered,
                new AttemptOutcome(status, null, retryAfter, duration, state.retryAttempt));
    }

    private String bodySnippet(ClientHttpResponse response) {
        if (!BufferedClientHttpResponse.isBuffered(response)) {
            return null;
        }
        String text = BufferedClientHttpResponse.bodyAsText(response);
        if (text == null) {
            return null;
        }
        String contentType = response.getHeaders().getContentType() == null
                ? null : response.getHeaders().getContentType().toString();
        return runtime.getRedactor().redactBody(text, contentType,
                runtime.getProperties().getLogging().getMaxBodySize());
    }

    // --------------------------------------------------------------------------- failure translation

    private RuntimeException translateFailure(CallState state, Throwable failure) {
        String correlationId = runtime.getCallContext().correlationId();
        String where = state.method.name() + " " + state.uriTemplate;
        if (TransportFailures.isTimeout(failure)) {
            return new RestClientTimeoutException(runtime.getName(), correlationId,
                    where + " timed out", "read", runtime.getProperties().getReadTimeout(), failure);
        }
        if (TransportFailures.isConnectFailure(failure)) {
            return new RestClientConnectionException(runtime.getName(), correlationId,
                    where + " could not be reached: " + failure, failure);
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        return new RestClientConnectionException(runtime.getName(), correlationId,
                where + " failed: " + failure, failure);
    }

    private RestClientCallNotPermittedException notPermitted(String policy, RuntimeException cause) {
        runtime.getMeters().callNotPermitted(runtime.getName(), policy);
        return new RestClientCallNotPermittedException(runtime.getName(),
                runtime.getCallContext().correlationId(),
                "the call was not made: " + policy + " refused it", policy, cause);
    }

    private RuntimeException unwrapCompletion(CompletionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof BulkheadFullException) {
            return notPermitted("bulkhead", (BulkheadFullException) cause);
        }
        return cause instanceof RuntimeException runtime ? runtime : ex;
    }

    // ------------------------------------------------------------------------------------- plumbing

    private void audit(CallState state, ClientHttpResponse response, RuntimeException failure,
                       Duration duration) {
        if (!runtime.getAudit().enabled()) {
            return;
        }
        CallContextSource context = runtime.getCallContext();
        runtime.getAudit().record(new AuditRecorder.AuditRecord(
                state.method.name(), state.uriTemplate, state.lastStatus,
                Outcomes.of(state.lastStatus, failure), duration, state.attemptNumber,
                context.correlationId(), context.traceId(), context.principal(),
                runtime.getRedactor().redact(state.headers), state.lastResponseHeaders,
                failure == null ? null : failure.getClass().getName()));
    }

    private void close(ClientHttpResponse response) {
        try {
            response.close();
        } catch (RuntimeException ex) {
            // Closing a response that is already broken must not replace the failure being handled.
            runtime.getExchangeLogger().logFailure(null, ex, Duration.ZERO);
        }
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RestClientConnectionException(runtime.getName(),
                    runtime.getCallContext().correlationId(),
                    "interrupted while waiting to retry", ex);
        }
    }

    /** One attempt's response - {@code null} when it threw - and its outcome. */
    private record AttemptResult(ClientHttpResponse response, AttemptOutcome outcome) {
    }

    /**
     * Mutable state of one logical call.
     *
     * <p>Confined to the calling thread for the whole of {@link #execute}, so no synchronization is
     * needed; it is a class rather than a pile of local variables because the retry loop, the
     * decorators and the audit all need the same half-dozen values.
     */
    private final class CallState {

        private final HttpMethod method;
        private final URI uri;
        private final String uriTemplate;
        private final HttpHeaders headers;
        private final byte[] body;
        private final RequestOverrides overrides;

        private int attemptNumber = 1;
        private int retryAttempt = 1;
        private boolean credentialRejected;
        private boolean credentialRetryUsed;
        private int lastStatus;
        private Map<String, List<String>> lastResponseHeaders = Map.of();
        private AttemptOutcome lastOutcome;

        // CHECKSTYLE.OFF: ParameterNumber - the six immutable facts of one call.
        private CallState(HttpMethod method, URI uri, String uriTemplate, HttpHeaders headers,
                          byte[] body, RequestOverrides overrides) {
            this.method = method;
            this.uri = uri;
            this.uriTemplate = uriTemplate;
            this.headers = headers;
            this.body = body;
            this.overrides = overrides;
        }
        // CHECKSTYLE.ON: ParameterNumber

        private ru.ludwigandreas.restclient.spi.OutboundRequest describe() {
            return new ru.ludwigandreas.restclient.spi.OutboundRequest(
                    runtime.getName(), method.name(), uri, uriTemplate,
                    runtime.getExchangeLogger().enabled()
                            && runtime.getProperties().getLogging().getLevel().includes(LogDetail.HEADERS)
                            ? runtime.getRedactor().redact(headers) : Map.of(),
                    attemptNumber);
        }
    }
}
