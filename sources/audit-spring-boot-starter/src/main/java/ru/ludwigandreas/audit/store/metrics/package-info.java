/**
 * Counters for the trail.
 *
 * <p>Optional: the whole package is behind {@code @ConditionalOnClass(MeterRegistry.class)}, because a
 * library must not drag an observability stack into an application that did not ask for one. Without
 * Micrometer the failure listener is the no-op and {@code FailurePolicyAuditSink} still logs.
 */
package ru.ludwigandreas.audit.store.metrics;
