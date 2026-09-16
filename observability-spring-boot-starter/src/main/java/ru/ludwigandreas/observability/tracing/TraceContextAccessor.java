package ru.ludwigandreas.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Optional;

/**
 * Reads the current trace and span ids, and does the right thing when tracing is absent.
 *
 * <p>Three call sites need these ids - the JSON log encoder, the correlation filter that seeds an
 * id from the trace, and the response header that hands the trace id back to the caller - and each
 * has to cope with the same three situations: tracing not on the classpath at all, tracing present
 * but this code running outside any span, and a span that exists. Spreading that handling across
 * three classes is how one of them ends up throwing a {@code NullPointerException} on a code path
 * that only occurs in an unsampled request in production.
 *
 * <p>So the awkwardness lives here, behind two methods that return {@link Optional} and never throw.
 */
public class TraceContextAccessor {

    /** Null when the application has no tracer, which is a supported configuration, not an error. */
    private final Tracer tracer;

    public TraceContextAccessor(Tracer tracer) {
        this.tracer = tracer;
    }

    /** The current trace id, or empty when nothing is tracing here. */
    public Optional<String> currentTraceId() {
        return currentSpan().map(span -> span.context().traceId()).filter(id -> !id.isBlank());
    }

    /** The current span id, or empty when nothing is tracing here. */
    public Optional<String> currentSpanId() {
        return currentSpan().map(span -> span.context().spanId()).filter(id -> !id.isBlank());
    }

    /** The trace id as a plain nullable string, for the suppliers that want one. */
    public String currentTraceIdOrNull() {
        return currentTraceId().orElse(null);
    }

    /** The current span itself, for callers that want to tag it rather than read its ids. */
    public Optional<Span> currentSpan() {
        if (tracer == null) {
            return Optional.empty();
        }
        Span span = tracer.currentSpan();
        // A no-op span is what an unsampled request or a tracer-less context yields. Its ids are
        // all-zero placeholders rather than null, and exporting those into a log field would fill the
        // aggregator with a trace id that resolves to nothing and looks like a broken exporter.
        if (span == null || span.isNoop()) {
            return Optional.empty();
        }
        return Optional.of(span);
    }
}
