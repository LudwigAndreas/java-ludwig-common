package ru.ludwigandreas.webcore.trace;

import java.util.Optional;
import org.slf4j.MDC;

/**
 * Reads the trace id out of the SLF4J MDC.
 *
 * <p>Deliberately not a dependency on any tracing library. Micrometer Tracing, Sleuth, an OpenTelemetry
 * agent and a hand-written servlet filter all agree on one thing - they put the id in the MDC under a
 * well-known key - so reading it from there works with whichever of them a service happens to use, and
 * with none of them installed it simply finds nothing.
 */
public class MdcTraceIdProvider implements TraceIdProvider {

    private final String mdcKey;

    public MdcTraceIdProvider(String mdcKey) {
        this.mdcKey = mdcKey;
    }

    @Override
    public Optional<String> currentTraceId() {
        String traceId = MDC.get(mdcKey);
        return traceId == null || traceId.isBlank() ? Optional.empty() : Optional.of(traceId);
    }
}
