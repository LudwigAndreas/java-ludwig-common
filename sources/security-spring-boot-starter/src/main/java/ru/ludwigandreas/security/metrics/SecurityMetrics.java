package ru.ludwigandreas.security.metrics;

/**
 * Instrumentation hook for the authentication/authorization path. {@link NoopSecurityMetrics} is the
 * always-available fallback; {@link MicrometerSecurityMetrics} replaces it when Micrometer is on the
 * classpath and {@code ludwig.security.metrics.enabled=true} (the default).
 *
 * <p>The counters here are the ones worth alerting on. A jump in {@code authenticationFailed} with
 * reason {@code untrusted-proxy} means something is talking to a service directly instead of through
 * the mesh; a jump in {@code accessDenied} for one resource type usually means a role mapping was
 * changed or a projection went stale.
 */
public interface SecurityMetrics {

    void recordAuthenticated(String principalType);

    void recordAuthenticationFailed(String principalType, String reason);

    void recordAccessDenied(String resourceType, String action);

    void recordAuthorityCacheHit(String principalType);

    void recordAuthorityCacheMiss(String principalType);

    /** How often a query ran restricted rather than unrestricted - the data-scope coverage signal. */
    void recordDataScopeApplied(String resourceType, String access);
}
