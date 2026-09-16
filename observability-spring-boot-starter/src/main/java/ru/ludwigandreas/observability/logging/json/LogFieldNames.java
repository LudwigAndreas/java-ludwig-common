package ru.ludwigandreas.observability.logging.json;

import ru.ludwigandreas.observability.config.ObservabilityProperties;

/**
 * The JSON field names one log line is written with.
 *
 * <h2>Why this is a choice and not a constant</h2>
 *
 * <p>A log aggregator only makes a field queryable if it recognises it. Elasticsearch ships index
 * templates and dashboards keyed on Elastic Common Schema names; an OpenTelemetry collector expects
 * the log data model's names; Loki and the smaller stacks mostly want something short and flat. Emit
 * the wrong set and every line still arrives, still parses, and none of the built-in dashboards
 * show anything - which is discovered late, because nothing failed.
 *
 * <p>Rather than pick one and make every other estate write ingest-time rename rules, the encoder
 * takes its names from here.
 *
 * <h2>Dotted names stay dotted</h2>
 *
 * <p>ECS names like {@code log.level} are written as literal dotted keys rather than as nested
 * objects. That is what the reference ECS logging libraries emit, and Elasticsearch expands dotted
 * keys on ingest - whereas a document that nests {@code log} as an object in some lines and uses it
 * as a prefix in others produces a mapping conflict that rejects the whole batch.
 *
 * @param severityNumber present only for schemes that carry a numeric severity; null otherwise
 */
public record LogFieldNames(
        String timestamp,
        String level,
        String severityNumber,
        String logger,
        String thread,
        String message,
        String messageTemplate,
        String traceId,
        String spanId,
        String correlationId,
        String serviceName,
        String serviceNamespace,
        String serviceVersion,
        String serviceEnvironment,
        String serviceInstance,
        String errorType,
        String errorMessage,
        String stackTrace,
        String markers) {

    /** Elastic Common Schema, for an Elasticsearch or OpenSearch backend. */
    public static LogFieldNames ecs() {
        return new LogFieldNames(
                "@timestamp", "log.level", null, "log.logger", "process.thread.name",
                "message", "event.original", "trace.id", "span.id", "correlation.id",
                "service.name", "service.namespace", "service.version", "service.environment",
                "service.node.name", "error.type", "error.message", "error.stack_trace", "tags");
    }

    /** OpenTelemetry log data model, for a collector-based pipeline. */
    public static LogFieldNames otel() {
        return new LogFieldNames(
                "Timestamp", "SeverityText", "SeverityNumber", "InstrumentationScope", "ThreadName",
                "Body", "BodyTemplate", "TraceId", "SpanId", "CorrelationId",
                "service.name", "service.namespace", "service.version", "deployment.environment",
                "service.instance.id", "ExceptionType", "ExceptionMessage", "ExceptionStackTrace", "Markers");
    }

    /** Short, shallow, snake_case names for a stack with no schema of its own. */
    public static LogFieldNames flat() {
        return new LogFieldNames(
                "timestamp", "level", null, "logger", "thread",
                "message", "message_template", "trace_id", "span_id", "correlation_id",
                "service", "namespace", "version", "environment",
                "instance", "error_type", "error_message", "stack_trace", "markers");
    }

    public static LogFieldNames of(ObservabilityProperties.Logging.FieldSet fieldSet) {
        return switch (fieldSet) {
            case ECS -> ecs();
            case OTEL -> otel();
            case FLAT -> flat();
        };
    }
}
