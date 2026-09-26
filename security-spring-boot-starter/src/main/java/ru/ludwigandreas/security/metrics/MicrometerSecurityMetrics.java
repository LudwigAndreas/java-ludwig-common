package ru.ludwigandreas.security.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * Micrometer implementation.
 *
 * <p>Every tag value here is a bounded, server-side vocabulary - principal type, resource type,
 * action, a fixed reason enum. Nothing caller-controlled (subject, partner id, request path) is ever
 * used as a tag: that would let an external caller drive cardinality in the metrics backend, which is
 * both a cost problem and a denial-of-service vector. Per-subject detail belongs in the audit log
 * (the platform's {@link ru.ludwigandreas.audit.AuditSink}), which is built to hold it.
 */
@RequiredArgsConstructor
public class MicrometerSecurityMetrics implements SecurityMetrics {

    private final MeterRegistry registry;

    @Override
    public void recordAuthenticated(String principalType) {
        registry.counter("ludwig.security.authentication", "principal.type", principalType,
                "outcome", "success").increment();
    }

    @Override
    public void recordAuthenticationFailed(String principalType, String reason) {
        registry.counter("ludwig.security.authentication", "principal.type", principalType,
                "outcome", "failure", "reason", reason).increment();
    }

    @Override
    public void recordAccessDenied(String resourceType, String action) {
        registry.counter("ludwig.security.access.denied", "resource", resourceType,
                "action", action).increment();
    }

    @Override
    public void recordAuthorityCacheHit(String principalType) {
        registry.counter("ludwig.security.authorities.cache", "principal.type", principalType,
                "result", "hit").increment();
    }

    @Override
    public void recordAuthorityCacheMiss(String principalType) {
        registry.counter("ludwig.security.authorities.cache", "principal.type", principalType,
                "result", "miss").increment();
    }

    @Override
    public void recordDataScopeApplied(String resourceType, String access) {
        registry.counter("ludwig.security.data.scope", "resource", resourceType,
                "access", access).increment();
    }
}
