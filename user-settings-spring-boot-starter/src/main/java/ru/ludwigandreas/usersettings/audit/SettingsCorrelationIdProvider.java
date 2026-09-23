package ru.ludwigandreas.usersettings.audit;

import java.util.Optional;

/**
 * The current request's correlation id, for the audit trail.
 *
 * <p>An interface of this module's own rather than a direct dependency on
 * {@code observability-spring-boot-starter}: the audit recorder is always wired, the observability
 * module is optional, and a bean that referenced its classes directly would make the optional
 * dependency mandatory in practice. The autoconfiguration binds this to the observability module's
 * {@code CorrelationContext} when it is present, to web-core's {@code TraceIdProvider} when it is
 * not, and to {@link #none()} when neither is.
 *
 * <p>Worth the indirection because of what the id is for: it is the only thing that joins an audit
 * row to the logs, traces and downstream calls of the same request. "Who changed this setting" is
 * answerable without it; "what else happened in that request, and what did the user actually do"
 * is not.
 */
@FunctionalInterface
public interface SettingsCorrelationIdProvider {

    Optional<String> currentCorrelationId();

    /** For a deployment with no correlation mechanism at all. */
    static SettingsCorrelationIdProvider none() {
        return Optional::empty;
    }
}
