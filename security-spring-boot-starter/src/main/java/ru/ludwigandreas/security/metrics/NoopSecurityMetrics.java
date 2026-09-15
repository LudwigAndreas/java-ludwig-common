package ru.ludwigandreas.security.metrics;

/** No-op fallback, so no call site needs a null check on the metrics bean. */
public class NoopSecurityMetrics implements SecurityMetrics {

    @Override
    public void recordAuthenticated(String principalType) {
        // no-op
    }

    @Override
    public void recordAuthenticationFailed(String principalType, String reason) {
        // no-op
    }

    @Override
    public void recordAccessDenied(String resourceType, String action) {
        // no-op
    }

    @Override
    public void recordAuthorityCacheHit(String principalType) {
        // no-op
    }

    @Override
    public void recordAuthorityCacheMiss(String principalType) {
        // no-op
    }

    @Override
    public void recordDataScopeApplied(String resourceType, String access) {
        // no-op
    }
}
