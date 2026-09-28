package ru.ludwigandreas.restclient.resilience;

import java.util.Locale;
import java.util.Set;

/**
 * Which HTTP methods may be retried without asking.
 *
 * <p>Idempotence in the RFC 9110 sense: repeating the request has the same effect on the server as
 * making it once. That is a property of the <em>method's contract</em>, not of any particular
 * endpoint, which is exactly why this list is fixed rather than configurable - a service whose
 * {@code PUT} is not idempotent has a defect that a retry setting would only hide.
 *
 * <p>POST and PATCH are absent, and that absence is the whole point. A read timeout on a POST says
 * the answer did not arrive; it says nothing about whether the server processed it. Retrying is how
 * a payment is taken twice, and the resulting duplicate is nearly impossible to attribute weeks
 * later. A caller that has made its POST safe - an idempotency key, a natural unique constraint -
 * opts in per request with the {@code X-Ludwig-Retry} header.
 */
public final class IdempotentMethods {

    private static final Set<String> METHODS = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE");

    private IdempotentMethods() {
    }

    /** Whether {@code method} may be retried without an explicit opt-in. */
    public static boolean contains(String method) {
        return method != null && METHODS.contains(method.toUpperCase(Locale.ROOT));
    }
}
