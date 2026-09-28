package ru.ludwigandreas.restclient.core;

/**
 * The ambient facts about the unit of work a call belongs to: who, and under which ids.
 *
 * <p>An interface with three no-op defaults rather than direct dependencies on
 * observability-spring-boot-starter and Spring Security, because both are optional here. A batch
 * worker with neither on the classpath still gets a working client; it just gets audit records with
 * {@code null} where a principal would be, which is the truth.
 *
 * <p>The implementation the auto-configuration supplies delegates to
 * {@code ru.ludwigandreas.observability.correlation.CorrelationContext} and to Micrometer's
 * {@code Tracer} when they are present - the platform already has one correlation id and one trace
 * context, and a second implementation of either would eventually disagree with the first.
 */
public interface CallContextSource {

    /** A source that knows nothing, for a context with neither observability nor security. */
    CallContextSource NONE = new CallContextSource() {
    };

    /** The platform correlation id of the current unit of work, or {@code null}. */
    default String correlationId() {
        return null;
    }

    /**
     * The header the correlation id travels in.
     *
     * <p>Read from {@code ludwig.observability.correlation.header-name} when that module is present,
     * so the id this client sends is the one the rest of the estate expects to receive. Hard-coding
     * it here would work until a deployment changed it in one place.
     */
    default String correlationHeaderName() {
        return "X-Correlation-Id";
    }

    /** The current W3C trace id, or {@code null} when nothing is being traced. */
    default String traceId() {
        return null;
    }

    /**
     * The authenticated subject on whose behalf the call is made, or {@code null}.
     *
     * <p>A subject identifier - the {@code sub} claim, a service account name - and never a token, a
     * session id or an email address: this value is written into retained audit records.
     */
    default String principal() {
        return null;
    }
}
