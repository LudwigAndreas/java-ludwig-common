package ru.ludwigandreas.audit;

import java.util.Optional;

/**
 * The current unit of work's correlation and trace ids, for the envelope.
 *
 * <p>An interface of this module's own rather than a dependency on
 * {@code observability-spring-boot-starter}, for the reason
 * {@code SettingsCorrelationIdProvider} already gave when it made the same choice: the audit path is
 * always wired and the observability module is optional, and a bean that referenced its classes
 * directly would make the optional dependency mandatory in practice. This module having no in-repo
 * dependencies at all makes that mandatory rather than merely preferable.
 *
 * <p>Worth the indirection because of what the id is for: it is the only thing that joins an audit
 * event to the logs, traces and downstream calls of the same request. "Who changed this" is
 * answerable without it; "what else happened in that request" is not.
 */
public interface CorrelationProvider {

    /** The platform correlation id bound to the current unit of work. */
    Optional<String> currentCorrelationId();

    /** The W3C trace id, when a trace was sampled. */
    default Optional<String> currentTraceId() {
        return Optional.empty();
    }

    /** For a deployment with no correlation mechanism at all. */
    static CorrelationProvider none() {
        return Optional::empty;
    }
}
