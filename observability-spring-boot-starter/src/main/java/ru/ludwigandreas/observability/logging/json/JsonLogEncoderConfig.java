package ru.ludwigandreas.observability.logging.json;

import java.util.List;
import java.util.Map;
import ru.ludwigandreas.observability.core.ServiceIdentity;

/**
 * Everything {@link JsonLogEncoder} needs, resolved once and handed over whole.
 *
 * <p>The encoder cannot look any of this up for itself. Logback owns the appender graph and builds
 * encoders outside the Spring container, so there is no injection point and no
 * {@code ObservabilityProperties} to read - which is exactly why this exists: the Spring side
 * resolves the configuration while it still can, and passes an immutable snapshot across the
 * boundary.
 *
 * <p>Immutability is the second reason. {@code encode} runs on every application thread that logs,
 * with no synchronization; a configuration object that could be mutated afterwards - by a refresh,
 * by a late setter - would be read concurrently while changing, and the resulting torn output is
 * both rare and unreproducible.
 *
 * @param fieldNames         the naming scheme the line is written with
 * @param identity           service identity stamped on every line
 * @param staticFields       constant extra fields, e.g. cluster or region
 * @param includeMdc         whether the MDC is written at all
 * @param mdcIncludeKeys     allow-list; empty means all keys
 * @param mdcExcludeKeys     deny-list, applied after the allow-list
 * @param nestMdc            whether MDC entries are nested under {@code mdcFieldName}
 * @param mdcFieldName       object name MDC entries are nested under
 * @param maskedKeySubstrings keys whose values are replaced with {@code ***}, matched case-insensitively
 * @param traceIdMdcKey      MDC key Micrometer Tracing publishes the trace id under
 * @param spanIdMdcKey       MDC key Micrometer Tracing publishes the span id under
 * @param correlationIdMdcKey MDC key this module publishes the correlation id under
 * @param maxMessageLength   message truncation bound; {@code 0} disables truncation
 * @param maxStackTraceLength stack-trace truncation bound; {@code 0} disables truncation
 * @param includeThreadName  whether the logging thread is written
 * @param includeMarkers     whether SLF4J markers are written
 * @param includeMessageTemplate whether the pre-interpolation message is written alongside the formatted one
 */
public record JsonLogEncoderConfig(
        LogFieldNames fieldNames,
        ServiceIdentity identity,
        Map<String, String> staticFields,
        boolean includeMdc,
        List<String> mdcIncludeKeys,
        List<String> mdcExcludeKeys,
        boolean nestMdc,
        String mdcFieldName,
        List<String> maskedKeySubstrings,
        String traceIdMdcKey,
        String spanIdMdcKey,
        String correlationIdMdcKey,
        int maxMessageLength,
        int maxStackTraceLength,
        boolean includeThreadName,
        boolean includeMarkers,
        boolean includeMessageTemplate) {

    public JsonLogEncoderConfig {
        staticFields = Map.copyOf(staticFields);
        mdcIncludeKeys = List.copyOf(mdcIncludeKeys);
        mdcExcludeKeys = List.copyOf(mdcExcludeKeys);
        // Lower-cased once here rather than per key per log line: this list is consulted for every
        // MDC entry of every line, and case-folding it in the hot path would be pure waste.
        maskedKeySubstrings = maskedKeySubstrings.stream()
                .map(key -> key.toLowerCase(java.util.Locale.ROOT))
                .toList();
    }
}
