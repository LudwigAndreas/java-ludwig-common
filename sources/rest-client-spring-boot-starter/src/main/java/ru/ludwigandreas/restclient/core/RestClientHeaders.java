package ru.ludwigandreas.restclient.core;

import org.springframework.http.HttpHeaders;

/**
 * The per-request overrides, and the last of the five precedence levels.
 *
 * <p>They are headers because a header is the one thing that reaches the pipeline from every calling
 * style: a declarative interface method with a {@code @RequestHeader}, an injected
 * {@code RestClient}, an injected {@code WebClient}, and a {@code RestClientRegistry} lookup all set
 * headers the same way. A {@code ThreadLocal} would have been prettier for the blocking case and
 * would not have survived a single {@code flatMap}.
 *
 * <p>Every one of them is <strong>consumed and removed</strong> before the request is sent. They are
 * instructions to this starter, not to the peer, and a partner receiving
 * {@code X-Ludwig-Retry: true} would at best ignore it and at worst log it.
 */
public final class RestClientHeaders {

    /**
     * {@code true} to retry this request even though its method is not idempotent, {@code false} to
     * suppress retries that would otherwise happen.
     *
     * <p>The opt-in is the supported way to retry a POST: the caller is asserting that it has made
     * the request safe to repeat - an idempotency key, a natural unique constraint - which is
     * knowledge the starter cannot have. The opt-out is for a GET whose result must not be
     * re-fetched, which is rarer and just as legitimate.
     */
    public static final String RETRY = "X-Ludwig-Retry";

    /**
     * Lowers the attempt limit for this request only.
     *
     * <p>Lowers, never raises. A call made from a request thread with 200ms left on its own budget
     * has every right to ask for one attempt; letting it ask for ten would let one call site
     * override a capacity decision taken for the whole deployment.
     */
    public static final String MAX_ATTEMPTS = "X-Ludwig-Max-Attempts";

    private RestClientHeaders() {
    }

    /**
     * Reads the overrides from {@code headers} and deletes them.
     *
     * <p>Reading and deleting in one step, in one place, is what guarantees they cannot leak: there
     * is no code path that reads one without removing it.
     */
    public static RequestOverrides consume(HttpHeaders headers) {
        Boolean retry = parseBoolean(headers.getFirst(RETRY));
        Integer maxAttempts = parseInt(headers.getFirst(MAX_ATTEMPTS));
        headers.remove(RETRY);
        headers.remove(MAX_ATTEMPTS);
        return new RequestOverrides(retry, maxAttempts);
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        // Only the two exact spellings count. Treating an unrecognized value as `false` would make a
        // typo silently disable a retry the caller asked for; treating it as `true` would enable one
        // it did not. Ignoring it falls back to the configured policy, which is the safe default.
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        return "false".equalsIgnoreCase(value) ? Boolean.FALSE : null;
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
