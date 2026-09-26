package ru.ludwigandreas.idempotency.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimOutcome;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.IdempotencyHeaders;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.api.StoredResponse;
import ru.ludwigandreas.idempotency.audit.IdempotencyAuditEvent;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.error.ClaimInProgressException;
import ru.ludwigandreas.idempotency.error.ClaimNotReplayableException;
import ru.ludwigandreas.idempotency.error.FingerprintMismatchException;
import ru.ludwigandreas.idempotency.error.IdempotencyKeyRequiredException;
import ru.ludwigandreas.idempotency.error.IdempotencyKeyTooLongException;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;

/**
 * The HTTP surface: claims the request's key, replays a completed duplicate's response, and refuses the
 * two duplicates that must not be answered.
 *
 * <h2>The four paths</h2>
 *
 * <ol>
 *   <li>a <b>fresh</b> key: the handler runs, and its response is stored on the claim;</li>
 *   <li>a <b>completed</b> duplicate: the stored response is replayed and <em>nothing runs</em>. This is
 *       the part a duplicate flag cannot do, and it is what makes the feature real for an API - a caller
 *       who retried a timed-out {@code POST} needs the original {@code 201} and its body, not a boolean
 *       saying "you already did this";</li>
 *   <li>an <b>in-flight</b> duplicate: 409 with {@code Retry-After}. Not the original response, which
 *       does not exist yet, and not a second execution;</li>
 *   <li>a key whose <b>fingerprint does not match</b>: 422. Never the first request's response - a client
 *       that recycles keys would otherwise receive somebody else's resource with a 200, which is a
 *       data-integrity failure that presents as "the API returned the wrong data" and leaves no trace.</li>
 * </ol>
 *
 * <p>A <b>failed</b> claim is not a fifth path: the claim statement reclaims it, so the retry of a failed
 * request arrives here as a fresh key. That is the behaviour a retry needs - a failed attempt must not
 * block the retry it exists to enable.
 *
 * <h2>Why a filter and not only an interceptor</h2>
 *
 * <p>Because the response has to be captured, and a {@code HandlerInterceptor} runs inside the response
 * that has already been committed by the time it sees it. A filter can wrap the response object, which is
 * what makes storing a body possible at all.
 *
 * <p>The cost is that an exception thrown here does not reach {@code @RestControllerAdvice} - Spring's
 * exception handling is per-<em>handler</em>, and a filter is outside it. So the 409 and the 422 are
 * rendered here, through {@code web-core}'s {@code ProblemDetailFactory} and its message bundles: the same
 * two steps the shared advice runs, against the same beans, producing the same document shape. This
 * module still ships no {@code @RestControllerAdvice} of its own, and there is still exactly one place
 * that knows what a problem document looks like.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not wrap the request or the response for a request it is not going to claim. A service where
 * one endpoint is protected should not pay a buffered body and a teed output stream on every other route,
 * so the matcher decides first and the wrapping happens after.
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter implements Ordered {

    /** The longest key the claim column holds. */
    private static final int MAX_KEY_LENGTH = 255;

    /** The lowest status that means the work was not done. */
    private static final int FIRST_ERROR_STATUS = 400;

    private final IdempotencyStore store;
    private final IdempotencyEndpointMatcher matcher;
    private final RequestFingerprint fingerprints;
    private final ProblemDetailFactory problems;
    private final IdempotencyProperties properties;
    private final IdempotencyMetrics metrics;
    private final AuditSink audit;
    private final ActorResolver actors;
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param store        the claim store
     * @param matcher      decides which requests are claimed
     * @param fingerprints hashes a request so a recycled key is caught
     * @param problems     renders the 409 and the 422 as RFC 9457 documents
     * @param properties   the configuration
     * @param metrics      what this module reports about itself
     * @param audit        the trail a replay and a mismatch are recorded in
     * @param actors       resolves who made the call, for those two events
     * @param objectMapper serialises the problem document this filter has to write by hand
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a constructor whose arguments are all injected beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IdempotencyFilter(IdempotencyStore store, IdempotencyEndpointMatcher matcher,
                             RequestFingerprint fingerprints, ProblemDetailFactory problems,
                             IdempotencyProperties properties, IdempotencyMetrics metrics,
                             AuditSink audit, ActorResolver actors, ObjectMapper objectMapper) {
        this.store = store;
        this.matcher = matcher;
        this.fingerprints = fingerprints;
        this.problems = problems;
        this.properties = properties;
        this.metrics = metrics;
        this.audit = audit;
        this.actors = actors;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return properties.getHttp().getOrder();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(IdempotencyHeaders.KEY);
        if (key == null || key.isBlank()) {
            if (matcher.keyRequired(request)) {
                write(response, new IdempotencyKeyRequiredException(), request);
                return;
            }
            // No key and none demanded: a fire-and-forget caller who accepts a duplicate on a retry is a
            // real caller, and forcing a key on them would only produce random values that protect
            // nothing while making the table grow.
            chain.doFilter(request, response);
            return;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            write(response, new IdempotencyKeyTooLongException(key.length(), MAX_KEY_LENGTH), request);
            return;
        }
        claimAndProceed(request, response, chain, key);
    }

    /** The claim, and whichever of the four paths it chose. */
    private void claimAndProceed(HttpServletRequest request, HttpServletResponse response,
                                 FilterChain chain, String key) throws ServletException, IOException {
        String scope = matcher.scopeOf(request);
        CachedBodyRequest buffered = buffer(request);
        String fingerprint = fingerprintOf(buffered, request);

        UUID requestId = UUID.randomUUID();
        ClaimResult claim = store.claim(new ClaimRequest(
                ClaimMode.STANDALONE, scope, key, requestId,
                fingerprint, properties.ttlFor(scope), properties.leaseFor(scope)));

        if (claim.fingerprintMismatch(fingerprint)) {
            metrics.fingerprintMismatch(scope);
            audit.record(IdempotencyAuditEvent
                    .fingerprintMismatch(scope, key, claim.owner(), actors.currentActorOrSystem())
                    .toAuditEvent());
            log.warn("Idempotency key {}/{} was reused for a different request; refusing it", scope, key);
            write(response, new FingerprintMismatchException(scope, key), request);
            return;
        }
        if (!claim.won()) {
            answerDuplicate(request, response, claim, scope, key);
            return;
        }
        execute(buffered == null ? request : buffered, response, chain, scope, key, requestId);
    }

    /** Replays a completed duplicate, or tells an in-flight one to come back. */
    private void answerDuplicate(HttpServletRequest request, HttpServletResponse response,
                                 ClaimResult claim, String scope, String key) throws IOException {
        if (claim.outcome() == ClaimOutcome.IN_PROGRESS) {
            write(response, new ClaimInProgressException(key, retryAfter(claim)), request);
            return;
        }
        StoredResponse stored = claim.replayable().orElse(null);
        if (stored == null) {
            // The work was done and no response was stored, which now means only one thing: it was too large
            // to keep, or it fell outside the configured stored status range. Answered with its own code
            // rather than as an in-flight collision, because a Retry-After here would send the caller round
            // a loop that cannot end until the claim's window passes.
            write(response, new ClaimNotReplayableException(key), request);
            return;
        }
        metrics.replayed(scope);
        audit.record(IdempotencyAuditEvent.replayed(scope, key, claim.owner(), actors.currentActorOrSystem())
                .toAuditEvent());
        replay(response, stored, key);
    }

    /** Runs the handler and stores what it produced. */
    private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                         String scope, String key, UUID requestId) throws ServletException, IOException {
        CapturingResponse capture =
                new CapturingResponse(response, properties.getHttp().getMaxStoredResponse());
        capture.setHeader(IdempotencyHeaders.ECHO, key);
        boolean settled = false;
        try {
            chain.doFilter(request, capture);
            capture.flushBuffer();
            settle(capture, scope, key, requestId);
            settled = true;
        } finally {
            if (!settled) {
                // The handler threw past the dispatcher's own exception handling, or the container gave up
                // on the response. The claim is released so the caller's retry is allowed through: a failed
                // attempt must not block the retry it exists to enable. Done in a finally rather than a
                // catch so that it happens for an Error too - a key left in progress by an
                // OutOfMemoryError is a key nobody can use until its lease expires.
                metrics.failed(scope);
                store.fail(scope, key, requestId, "the handler did not complete");
            }
        }
    }

    /**
     * Records the outcome of the handler's run against the claim.
     *
     * <p>The distinction that matters is not "did an exception escape" but <b>what the caller was told</b>. A
     * handler that answered a 4xx or a 5xx did not do the work - and in this service's case the shared
     * {@code @RestControllerAdvice} has already turned the exception into a problem document, so nothing
     * escapes to the {@code finally} above and the claim would otherwise be marked completed for work that
     * never happened. Two consequences, both bad: a retry of a transiently failed request would be answered
     * with the failure forever, and a retry of a request the client <em>corrected</em> would be refused as a
     * fingerprint mismatch rather than allowed to succeed.
     *
     * <p>So any answer at or above 400 frees the key. A success completes the claim, with the response
     * attached when it is one this deployment stores.
     */
    private void settle(CapturingResponse capture, String scope, String key, UUID requestId) {
        int status = capture.getStatus();
        if (status >= FIRST_ERROR_STATUS) {
            metrics.failed(scope);
            store.fail(scope, key, requestId, "the handler answered " + status);
            return;
        }
        store.complete(scope, key, requestId, storable(capture));
    }

    /**
     * The response to store, or null when it must not be replayed.
     *
     * <p>Three reasons not to store one, and each one is a case where replaying would be worse than not:
     * a truncated body is a valid-looking response missing its end; a 4xx would replay a client's own
     * mistake back at it forever, including after the mistake was fixed; a 5xx would make a transient
     * failure permanent for that key.
     */
    private StoredResponse storable(CapturingResponse capture) {
        IdempotencyProperties.Http config = properties.getHttp();
        int status = capture.getStatus();
        if (capture.truncated()) {
            log.debug("Response to a claimed request outgrew {} bytes; storing nothing to replay",
                    config.getMaxStoredResponse());
            return null;
        }
        if (status < config.getMinStoredStatus() || status > config.getMaxStoredStatus()) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : config.getReplayedHeaders()) {
            String value = capture.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return new StoredResponse(status, capture.getContentType(), headers, capture.captured());
    }

    /** Writes a stored response back out, byte for byte, marked as a replay. */
    private void replay(HttpServletResponse response, StoredResponse stored, String key)
            throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        stored.headers().forEach(response::setHeader);
        response.setHeader(IdempotencyHeaders.REPLAYED, "true");
        response.setHeader(IdempotencyHeaders.ECHO, key);
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * Buffers the body, if it is small enough and fingerprinting is on.
     *
     * @return the wrapped request, or null when the original should be passed through untouched
     */
    private CachedBodyRequest buffer(HttpServletRequest request) throws IOException {
        IdempotencyProperties.Http config = properties.getHttp();
        if (!config.isFingerprintRequests()) {
            return null;
        }
        int declared = request.getContentLength();
        if (declared > config.getMaxFingerprintedBody()) {
            log.debug("Request body of {} bytes is past the {}-byte fingerprinting limit; claiming without"
                    + " a fingerprint", declared, config.getMaxFingerprintedBody());
            return null;
        }
        try (InputStream body = request.getInputStream()) {
            byte[] bytes = StreamUtils.copyToByteArray(body);
            if (bytes.length > config.getMaxFingerprintedBody()) {
                // A chunked request declares no length, so the limit can only be enforced after reading.
                // The bytes are already buffered at this point, so they are handed on rather than thrown
                // away - what is given up is the fingerprint, not the request.
                log.debug("Chunked request body of {} bytes is past the fingerprinting limit; claiming"
                        + " without a fingerprint", bytes.length);
                return new CachedBodyRequest(request, bytes);
            }
            return new CachedBodyRequest(request, bytes);
        }
    }

    /** The request's fingerprint, or null when the body was not buffered. */
    private String fingerprintOf(CachedBodyRequest buffered, HttpServletRequest request) {
        if (buffered == null || !properties.getHttp().isFingerprintRequests()) {
            return null;
        }
        if (buffered.body().length > properties.getHttp().getMaxFingerprintedBody()) {
            return null;
        }
        return fingerprints.of(request.getMethod(), request.getRequestURI(), request.getContentType(),
                buffered.body());
    }

    /**
     * How long an in-flight duplicate should wait.
     *
     * <p>Derived from the holder's lease rather than from a constant, and floored at one second: a
     * {@code Retry-After: 0} invites an immediate retry that will collide again, and a client that honours
     * it produces a tight loop against a request that has not finished.
     */
    private static Duration retryAfter(ClaimResult claim) {
        Duration remaining = Duration.between(Instant.now(), claim.expiresAt());
        return remaining.isNegative() || remaining.isZero() ? Duration.ofSeconds(1) : remaining;
    }

    /**
     * Renders one of this module's refusals as a problem document.
     *
     * <p>Through {@code web-core}'s factory, so the document has the same members, the same
     * {@code code}/{@code title}/{@code detail} vocabulary and the same trace id as every other error the
     * service emits - and the text comes from this module's bundle, in the caller's language. Serialising
     * it here rather than through Jackson's {@code HttpMessageConverter} is unavoidable: a filter has no
     * converter, and this is the one place in the module that writes a response by hand.
     */
    private void write(HttpServletResponse response, LocalizedException failure,
                       HttpServletRequest request) throws IOException {
        ProblemDetail problem = problems.create(failure, request.getRequestURI());
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Explicitly, and written as bytes below rather than through getWriter(). A servlet's default
        // response encoding is ISO-8859-1, and these messages are localized: the Russian bundle would be
        // delivered as mojibake, which is the sort of defect that survives every test that only asserts a
        // status code.
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (failure instanceof ClaimInProgressException inProgress) {
            response.setHeader("Retry-After", Long.toString(
                    Math.max(1L, inProgress.getRetryAfter().toSeconds())));
        }
        String key = request.getHeader(IdempotencyHeaders.KEY);
        if (key != null) {
            response.setHeader(IdempotencyHeaders.ECHO, key);
        }
        byte[] document = problemJson(problem).getBytes(StandardCharsets.UTF_8);
        response.setContentLength(document.length);
        response.getOutputStream().write(document);
        response.flushBuffer();
    }

    /**
     * The problem document as JSON.
     *
     * <p>Assembled with the module's own mapper rather than the application's, because a filter is outside
     * the message-converter machinery and there is no {@code HttpMessageConverter} to ask. The members are
     * whatever the factory put on the document, so a service that configured extra problem members gets
     * them here too.
     */
    private String problemJson(ProblemDetail problem) throws JsonProcessingException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", problem.getType() == null ? null : problem.getType().toString());
        document.put("title", problem.getTitle());
        document.put("status", problem.getStatus());
        document.put("detail", problem.getDetail());
        document.put("instance", problem.getInstance() == null ? null : problem.getInstance().toString());
        if (problem.getProperties() != null) {
            document.putAll(problem.getProperties());
        }
        document.values().removeIf(Objects::isNull);
        return objectMapper.writeValueAsString(document);
    }
}
