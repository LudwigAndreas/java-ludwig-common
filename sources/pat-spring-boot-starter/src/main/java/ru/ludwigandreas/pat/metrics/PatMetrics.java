package ru.ludwigandreas.pat.metrics;

/**
 * Instrumentation for the issuer. {@link NoopPatMetrics} is the always-available fallback;
 * {@link MicrometerPatMetrics} replaces it when Micrometer is on the classpath.
 *
 * <p>The counters here are the ones worth alerting on, and two of them are the only visibility that exists
 * for a condition no build in this repository can check.
 *
 * <ul>
 *   <li>{@link #recordExchangeFailure} carries the <b>reason</b> as a tag. The HTTP response deliberately
 *       does not - every exchange failure returns one indistinguishable body, because anything that
 *       distinguishes an unknown key id from a bad secret is an oracle for an attacker testing a guess. The
 *       reason has to live somewhere the defender can see it and the attacker cannot, and that somewhere is
 *       here. A jump in {@code revoked} specifically should page: presenting a revoked credential means
 *       something still holds it.</li>
 *   <li>{@link #recordExchange} is what makes a <b>non-caching edge</b> visible. The edge is not in this
 *       repository and nothing here can verify that it caches the exchange response for the lifetime the
 *       response declares - but if it does not, exchanges rise in proportion to request volume instead of
 *       staying proportional to distinct tokens per assertion lifetime. That step change is the closest
 *       thing to a check that exists, and an alert on it belongs to the deployment rather than to the
 *       build. Recorded in the change's enforcement table as one of the six conventions no build can
 *       hold.</li>
 * </ul>
 */
public interface PatMetrics {

    /** A successful exchange, tagged by audience so per-service traffic is visible. */
    void recordExchange(String audience);

    /** A refused exchange. The reason is tagged here and disclosed nowhere else. */
    void recordExchangeFailure(String reason);

    /** A request refused by the rate limiter, tagged by which limit bit. */
    void recordRateLimited(String limit);

    /**
     * A best-effort last-use write that did not happen.
     *
     * <p>Metered rather than only logged because the failure is deliberately swallowed: losing the write
     * costs an operator some precision in a dormancy report, and failing an exchange because a telemetry
     * write failed costs a production pipeline. A swallowed failure with no counter is a failure nobody
     * ever learns about, which is how a dormancy report quietly becomes fiction.
     */
    void recordLastUsedWriteFailed();
}
