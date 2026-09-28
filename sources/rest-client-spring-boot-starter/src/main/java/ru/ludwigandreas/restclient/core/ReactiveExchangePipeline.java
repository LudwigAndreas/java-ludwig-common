package ru.ludwigandreas.restclient.core;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
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
import ru.ludwigandreas.restclient.spi.OutboundRequest;
import ru.ludwigandreas.restclient.spi.OutboundResponse;
import ru.ludwigandreas.restclient.spi.ReactiveClientAuthenticator;

/**
 * The reactive execution pipeline: the same decisions as {@link SyncExchangePipeline}, none of the
 * waiting.
 *
 * <p>It is an {@code ExchangeFilterFunction} registered as the outermost filter, so a retry
 * re-subscribes to the whole downstream chain and every attempt gets a fresh {@code ClientRequest} -
 * which is what lets authentication run per attempt here exactly as it does in the blocking
 * pipeline.
 *
 * <p>The order is identical: {@code RateLimiter -> Bulkhead -> CircuitBreaker -> Retry ->
 * TimeLimiter -> [authenticate -> send]}. Only the mechanics differ: a permit is reserved rather
 * than waited for, the backoff is a {@code Mono.delay} rather than a {@code Thread.sleep}, and the
 * time limiter is a {@code timeout} operator rather than a second thread. Every decision - should
 * this be retried, how long is the wait, does this count against the breaker - comes from the same
 * {@code ClientResiliencePolicy} instance the blocking pipeline would have used.
 *
 * <p>Two settings are meaningless here and are refused at startup rather than ignored:
 * {@code bulkhead.max-wait-duration} (nothing waits for a permit, so a wait cannot be honoured) and
 * {@code bulkhead.type: THREAD_POOL} (there is no calling thread to release).
 */
public class ReactiveExchangePipeline implements ExchangeFilterFunction {

    private static final int UNAUTHORIZED = 401;
    private static final int FIRST_ERROR_STATUS = 400;

    private final ClientRuntime runtime;

    public ReactiveExchangePipeline(ClientRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        runtime.refreshIfStale();
        HttpHeaders mutable = new HttpHeaders();
        mutable.addAll(request.headers());
        RequestOverrides overrides = RestClientHeaders.consume(mutable);
        CallState state = new CallState(request, mutable, overrides);

        return Mono.deferContextual(context -> {
            long start = System.nanoTime();
            return withRateLimiter(withBulkhead(withBreaker(state, next)))
                    .doOnSuccess(response -> audit(state, null, elapsed(start)))
                    .doOnError(failure -> audit(state, failure, elapsed(start)));
        });
    }

    // ---------------------------------------------------------------- decorators, outermost first

    private Mono<ClientResponse> withRateLimiter(Mono<ClientResponse> next) {
        RateLimiter limiter = runtime.getResilience().getRateLimiter();
        if (limiter == null) {
            return next;
        }
        return Mono.defer(() -> {
            // reservePermission rather than waitForPermission: the blocking variant would park an
            // event-loop thread, which is the one thing a reactive client must never do.
            long waitNanos = limiter.reservePermission();
            if (waitNanos < 0) {
                return Mono.error(notPermitted("rate-limiter",
                        RequestNotPermitted.createRequestNotPermitted(limiter)));
            }
            return waitNanos == 0
                    ? next
                    : Mono.delay(Duration.ofNanos(waitNanos)).then(next);
        });
    }

    private Mono<ClientResponse> withBulkhead(Mono<ClientResponse> next) {
        Bulkhead bulkhead = runtime.getResilience().getBulkhead();
        if (bulkhead == null) {
            return next;
        }
        return Mono.defer(() -> {
            if (!bulkhead.tryAcquirePermission()) {
                return Mono.error(notPermitted("bulkhead", BulkheadFullException.createBulkheadFullException(
                        bulkhead)));
            }
            return next.doFinally(signal -> bulkhead.onComplete());
        });
    }

    private Mono<ClientResponse> withBreaker(CallState state, ExchangeFunction next) {
        CircuitBreaker breaker = runtime.getResilience().getCircuitBreaker();
        Mono<ClientResponse> call = Mono.defer(() -> attemptChain(state, next));
        if (breaker == null) {
            return call;
        }
        return Mono.defer(() -> {
            if (!breaker.tryAcquirePermission()) {
                return Mono.error(notPermitted("circuit-breaker",
                        CallNotPermittedException.createCallNotPermittedException(breaker)));
            }
            long start = System.nanoTime();
            return call
                    .doOnSuccess(response ->
                            breaker.onResult(System.nanoTime() - start, TimeUnit.NANOSECONDS,
                                    state.lastOutcome))
                    .doOnError(failure ->
                            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, failure));
        });
    }

    // ------------------------------------------------------------------------------- the retry loop

    /**
     * One attempt, and - recursively, through {@code Mono.defer} - the next one.
     *
     * <p>Recursion rather than {@code retryWhen}: the retry decision here depends on the
     * <em>response</em> as often as on an exception, and {@code retryWhen} only sees errors.
     * Turning a retryable 503 into a synthetic error so that {@code retryWhen} could see it would
     * mean re-materializing the response on the last attempt, which is exactly the kind of
     * indirection that makes two pipelines drift apart.
     */
    private Mono<ClientResponse> attemptChain(CallState state, ExchangeFunction next) {
        int maxAttempts = state.overrides.effectiveMaxAttempts(
                runtime.getResilience().getRetry().maxAttempts());
        long callStart = state.callStartNanos;
        return oneAttempt(state, next)
                .flatMap(response -> afterResponse(state, next, response, maxAttempts, callStart))
                .onErrorResume(failure -> afterFailure(state, next, failure, maxAttempts, callStart));
    }

    private Mono<ClientResponse> afterResponse(CallState state, ExchangeFunction next,
                                               ClientResponse response, int maxAttempts, long callStart) {
        AttemptOutcome outcome = state.lastOutcome;
        if (outcome.statusCode() == UNAUTHORIZED && !state.credentialRetryUsed
                && runtime.getAuthenticator().refreshOnUnauthorized()) {
            state.credentialRetryUsed = true;
            state.credentialRejected = true;
            state.attemptNumber++;
            runtime.getAuthenticator().invalidate();
            return response.releaseBody().then(Mono.defer(() -> attemptChain(state, next)));
        }
        if (!retryable(state, outcome, maxAttempts, millisSince(callStart))) {
            return Mono.just(response);
        }
        long wait = scheduleRetry(state, outcome);
        return response.releaseBody()
                .then(Mono.delay(Duration.ofMillis(wait)))
                .then(Mono.defer(() -> attemptChain(state, next)));
    }

    private Mono<ClientResponse> afterFailure(CallState state, ExchangeFunction next, Throwable failure,
                                              int maxAttempts, long callStart) {
        if (failure instanceof RestClientAuthenticationException
                || failure instanceof RestClientCallNotPermittedException) {
            return Mono.error(failure);
        }
        AttemptOutcome outcome = new AttemptOutcome(0, failure, -1, Duration.ZERO, state.retryAttempt);
        state.lastOutcome = outcome;
        if (!retryable(state, outcome, maxAttempts, millisSince(callStart))) {
            return Mono.error(translateFailure(state, failure));
        }
        long wait = scheduleRetry(state, outcome);
        return Mono.delay(Duration.ofMillis(wait)).then(Mono.defer(() -> attemptChain(state, next)));
    }

    private boolean retryable(CallState state, AttemptOutcome outcome, int maxAttempts, long elapsed) {
        if (outcome.attempt() >= maxAttempts) {
            return false;
        }
        return runtime.getResilience().getRetry()
                .shouldRetry(outcome, state.method.name(), state.overrides.retry(), elapsed);
    }

    private long scheduleRetry(CallState state, AttemptOutcome outcome) {
        long wait = runtime.getResilience().getRetry().waitMillis(outcome);
        String reason = runtime.getResilience().getRetry().reason(outcome);
        runtime.getMeters().retry(runtime.getName(), reason);
        runtime.getListeners().onRetry(state.describe(), outcome.attempt() + 1, wait, reason);
        state.retryAttempt++;
        state.attemptNumber++;
        return wait;
    }

    // ---------------------------------------------------------------------------------- one attempt

    private Mono<ClientResponse> oneAttempt(CallState state, ExchangeFunction next) {
        long start = System.nanoTime();
        return authenticate(state)
                .flatMap(authenticated -> {
                    runtime.getExchangeLogger().logRequest(state.describe(), null);
                    runtime.getListeners().onRequest(state.describe());
                    return applyTimeLimit(next.exchange(authenticated));
                })
                .flatMap(response -> observe(state, response, Duration.ofNanos(System.nanoTime() - start)))
                .doOnError(failure -> {
                    Duration duration = Duration.ofNanos(System.nanoTime() - start);
                    runtime.getExchangeLogger().logFailure(state.describe(), failure, duration);
                    runtime.getListeners().onError(state.describe(), failure);
                });
    }

    private Mono<ClientResponse> applyTimeLimit(Mono<ClientResponse> exchange) {
        TimeLimiter limiter = runtime.getResilience().getTimeLimiter();
        if (limiter == null) {
            return exchange;
        }
        // Per attempt, not per call: the retry budget is what bounds the whole call, and bounding
        // both at the same value would make the second attempt impossible.
        return exchange.timeout(limiter.getTimeLimiterConfig().getTimeoutDuration());
    }

    /**
     * Runs the authenticator for this attempt and rebuilds the request with whatever it produced.
     *
     * <p>{@link ReactiveClientAuthenticator} is preferred where the authenticator implements it,
     * because a reactive security context lives in the subscriber context and a
     * {@code CompletableFuture} cannot see it - which is exactly the case for token relay.
     */
    private Mono<ClientRequest> authenticate(CallState state) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(state.headers);
        URI target = RequestDefaults.withQueryParams(state.uri,
                runtime.getProperties().getDefaultQueryParams());
        MutableAuthRequest authRequest = new MutableAuthRequest(runtime.getName(), state.method,
                target, headers, state.attemptNumber, state.credentialRejected);

        Mono<Void> applied = runtime.getAuthenticator() instanceof ReactiveClientAuthenticator reactive
                ? reactive.authenticateReactive(authRequest)
                : Mono.fromFuture(() -> runtime.getAuthenticator().authenticateAsync(authRequest));

        return applied
                .onErrorMap(failure -> new RestClientAuthenticationException(runtime.getName(),
                        runtime.getCallContext().correlationId(),
                        "could not obtain credentials: " + failure.getMessage(),
                        runtime.getProperties().getAuth().getType(), state.credentialRejected, failure))
                .then(Mono.fromSupplier(() -> ClientRequest
                        .create(state.method, authRequest.uri())
                        .headers(outgoing -> outgoing.addAll(authRequest.headers()))
                        .attributes(attributes -> attributes.putAll(state.attributes))
                        .body(state.body)
                        .build()));
    }

    /** Reports the attempt, materializing the body only where that is safe. */
    private Mono<ClientResponse> observe(CallState state, ClientResponse response, Duration duration) {
        int status = response.statusCode().value();
        long retryAfter = RetryAfter.millis(response.headers().asHttpHeaders().getFirst("Retry-After"),
                runtime.getProperties().getResilience().getRetry().getMaxRetryAfter(), runtime.getClock());
        Map<String, List<String>> headers = runtime.getRedactor().redact(response.headers().asHttpHeaders());
        state.lastStatus = status;
        state.lastResponseHeaders = headers;
        state.lastOutcome = new AttemptOutcome(status, null, retryAfter, duration, state.retryAttempt);

        boolean wantBody = status >= FIRST_ERROR_STATUS || runtime.getExchangeLogger().wantsBody();
        if (!wantBody || !bufferable(response)) {
            report(state, headers, status, duration, null);
            return Mono.just(response);
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    report(state, headers, status, duration, snippet(response, body));
                    // The body has been consumed, so the response handed on has to be rebuilt around
                    // it. The original strategies are reused so a custom codec downstream still
                    // applies to the replayed body.
                    return ClientResponse.create(response.statusCode(), response.strategies())
                            .headers(target -> target.addAll(response.headers().asHttpHeaders()))
                            .body(body)
                            .build();
                });
    }

    private boolean bufferable(ClientResponse response) {
        long declared = response.headers().contentLength().orElse(-1);
        return declared >= 0 && declared <= BufferedClientHttpResponse.MAX_BUFFERED_BYTES;
    }

    private String snippet(ClientResponse response, String body) {
        String contentType = response.headers().contentType().map(Object::toString).orElse(null);
        return runtime.getRedactor().redactBody(body, contentType,
                runtime.getProperties().getLogging().getMaxBodySize());
    }

    private void report(CallState state, Map<String, List<String>> headers, int status, Duration duration,
                        String bodySnippet) {
        OutboundResponse outbound = new OutboundResponse(status, headers, duration, bodySnippet);
        runtime.getExchangeLogger().logResponse(state.describe(), outbound);
        runtime.getListeners().onResponse(state.describe(), outbound);
    }

    // --------------------------------------------------------------------------- failure translation

    private RuntimeException translateFailure(CallState state, Throwable failure) {
        String correlationId = runtime.getCallContext().correlationId();
        String where = state.method.name() + " " + state.uriTemplate;
        if (failure instanceof TimeoutException || TransportFailures.isTimeout(failure)) {
            TimeLimiter limiter = runtime.getResilience().getTimeLimiter();
            return new RestClientTimeoutException(runtime.getName(), correlationId,
                    where + " timed out",
                    limiter == null ? "read" : "time-limiter",
                    limiter == null ? runtime.getProperties().getReadTimeout()
                            : limiter.getTimeLimiterConfig().getTimeoutDuration(),
                    failure);
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

    // ------------------------------------------------------------------------------------- plumbing

    private void audit(CallState state, Throwable failure, Duration duration) {
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

    private Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    /**
     * Mutable state of one logical call.
     *
     * <p>Confined to one subscription: a {@code WebClient} exchange is a single chain with no
     * concurrent branches, and every mutation happens in an operator of that chain.
     */
    private final class CallState {

        private final HttpMethod method;
        private final URI uri;
        private final String uriTemplate;
        private final HttpHeaders headers;
        private final Map<String, Object> attributes;
        private final org.springframework.web.reactive.function.BodyInserter<?,
                ? super org.springframework.http.client.reactive.ClientHttpRequest> body;
        private final RequestOverrides overrides;
        private final long callStartNanos = System.nanoTime();

        private int attemptNumber = 1;
        private int retryAttempt = 1;
        private boolean credentialRejected;
        private boolean credentialRetryUsed;
        private int lastStatus;
        private Map<String, List<String>> lastResponseHeaders = Map.of();
        private AttemptOutcome lastOutcome;

        private CallState(ClientRequest request, HttpHeaders headers, RequestOverrides overrides) {
            this.method = request.method();
            this.uri = request.url();
            this.headers = headers;
            this.attributes = Map.copyOf(request.attributes());
            this.body = request.body();
            this.overrides = overrides;
            this.uriTemplate = ReactiveUriTemplates.resolve(request);
        }

        private OutboundRequest describe() {
            return new OutboundRequest(runtime.getName(), method.name(), uri, uriTemplate,
                    runtime.getExchangeLogger().enabled()
                            && runtime.getProperties().getLogging().getLevel().includes(LogDetail.HEADERS)
                            ? runtime.getRedactor().redact(headers) : Map.of(),
                    attemptNumber);
        }
    }
}
