package ru.ludwigandreas.observability.tracing;

import java.util.Optional;
import ru.ludwigandreas.webcore.trace.TraceIdProvider;

/**
 * Supplies web-core's problem documents with the real trace id, taken from the tracer rather than
 * guessed from an MDC key.
 *
 * <p>web-core ships {@code MdcTraceIdProvider}, which reads whichever MDC key it is configured with.
 * That is the right default for a service with no tracing, and it is one string literal away from
 * being wrong: rename the key on either side - or change tracing backends, and with it the key the
 * bridge populates - and every 500 response starts publishing an empty trace id, with nothing
 * failing to indicate it. Support then quotes an id that resolves to nothing.
 *
 * <p>When this starter is present it therefore takes over the binding and asks the tracer directly,
 * so the id on the problem body is by construction the id of the span that was in scope when the
 * request failed.
 *
 * <p>This class is only loaded when web-core is on the classpath - it is an optional dependency, and
 * the autoconfiguration that registers it is guarded on the interface being present.
 */
public class MicrometerTraceIdProvider implements TraceIdProvider {

    private final TraceContextAccessor traceContext;

    public MicrometerTraceIdProvider(TraceContextAccessor traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public Optional<String> currentTraceId() {
        return traceContext.currentTraceId();
    }
}
