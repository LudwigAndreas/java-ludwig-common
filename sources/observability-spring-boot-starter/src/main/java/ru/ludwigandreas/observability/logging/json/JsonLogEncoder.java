package ru.ludwigandreas.observability.logging.json;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.observability.core.ServiceIdentity;

/**
 * Writes each log event as one JSON object on one line.
 *
 * <h2>What this buys over a pattern layout</h2>
 *
 * <p>A pattern-formatted line has to be parsed back apart by the aggregator with a regular
 * expression that lives somewhere else, is maintained by someone else, and breaks silently whenever
 * a message contains the delimiter it keys on - a stack trace, a user-supplied string, a newline.
 * Emitting the fields already separated removes that whole class of failure, and it is what makes
 * queries like "every ERROR with this correlation id across four services" a filter rather than a
 * text search.
 *
 * <h2>Why a hand-written encoder rather than logstash-logback-encoder</h2>
 *
 * <p>The field set here is fixed and small, so it is written field by field with a streaming
 * generator and never reflects over an object. That matters for three reasons: the trace id,
 * correlation id and service identity are guaranteed present and guaranteed named consistently
 * rather than depending on how an appender was configured in each service's XML; the output cannot
 * be reshaped by the application's own Jackson configuration, which a shared {@code ObjectMapper}
 * absolutely would do; and one dependency fewer sits on the path that every log line of every
 * service flows through.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #encode} is called concurrently by every logging thread and holds no mutable state: the
 * configuration is an immutable snapshot, and the buffer and generator are created per call. Logback
 * serializes writes to the appender's output stream, not calls to the encoder.
 */
public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

    /** Starting size of the per-event buffer: enough for a typical line without having to grow. */
    private static final int INITIAL_BUFFER_BYTES = 512;

    /**
     * Severity numbers from the OpenTelemetry log data model, where each level owns a band of four
     * and the number given here is the band's base value.
     */
    private static final int SEVERITY_ERROR = 17;
    private static final int SEVERITY_WARN = 13;
    private static final int SEVERITY_INFO = 9;
    private static final int SEVERITY_DEBUG = 5;
    private static final int SEVERITY_TRACE = 1;
    private static final int SEVERITY_UNSPECIFIED = 0;

    /**
     * ISO-8601 in UTC with fixed millisecond precision.
     *
     * <p>Fixed precision on purpose: {@code DateTimeFormatter.ISO_INSTANT} omits trailing zeros, so
     * a timestamp on an exact second renders three characters shorter than its neighbours. Log
     * shippers that infer a timestamp format from the first lines they see then reject the rest.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String TRUNCATION_SUFFIX = "...[truncated]";

    /**
     * Shared and thread-safe by contract - {@code JsonFactory} is documented as such, and creating
     * one per event would allocate its symbol tables on every log line.
     */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private final JsonLogEncoderConfig config;

    public JsonLogEncoder(JsonLogEncoderConfig config) {
        this.config = config;
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        // The buffer is per-call and short-lived, which is precisely the allocation profile a
        // generational collector handles for free.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(INITIAL_BUFFER_BYTES);
        try (JsonGenerator json = JSON_FACTORY.createGenerator(buffer, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            LogFieldNames names = config.fieldNames();
            json.writeStartObject();

            json.writeStringField(names.timestamp(),
                    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.getTimeStamp())));
            json.writeStringField(names.level(), event.getLevel().toString());
            if (names.severityNumber() != null) {
                json.writeNumberField(names.severityNumber(), severityNumber(event.getLevel()));
            }
            json.writeStringField(names.logger(), event.getLoggerName());
            if (config.includeThreadName() && event.getThreadName() != null) {
                json.writeStringField(names.thread(), event.getThreadName());
            }
            json.writeStringField(names.message(), truncate(event.getFormattedMessage(), config.maxMessageLength()));
            if (config.includeMessageTemplate() && event.getMessage() != null) {
                json.writeStringField(names.messageTemplate(), truncate(event.getMessage(), config.maxMessageLength()));
            }

            // Fetched once and passed down: LoggingEvent.getMDCPropertyMap() is not a field read -
            // it resolves the MDC adapter through the logger context and copies the map - and it is
            // needed by both the promoted-id block and the MDC block.
            Map<String, String> mdc = event.getMDCPropertyMap();
            writeCorrelationFields(json, mdc, names);
            writeServiceIdentity(json, names);
            writeStaticFields(json);
            writeKeyValuePairs(json, event);
            writeMarkers(json, event, names);
            writeThrowable(json, event, names);
            writeMdc(json, mdc);

            json.writeEndObject();
        } catch (IOException e) {
            // A ByteArrayOutputStream cannot actually fail, so this is unreachable in practice. It is
            // still not allowed to propagate: an exception thrown out of encode() is thrown from
            // inside the application's logging call, turning a log statement into a failed request.
            // Logback's own status manager is the right place for it - visible, and inert.
            addError("Failed to encode log event as JSON", e);
            return new byte[0];
        }
        buffer.write('\n');
        return buffer.toByteArray();
    }

    /**
     * Promotes the trace, span and correlation ids out of the MDC into first-class fields.
     *
     * <p>They arrive in the MDC - Micrometer Tracing's SLF4J bridge puts the trace and span ids
     * there, and this module's filters put the correlation id there - but they are not "some label
     * the application happened to set". They are the join keys the entire pipeline is built on, so
     * they get stable schema-correct names of their own, and {@link #writeMdc} then skips them so
     * that no line carries the same id twice under two different names.
     */
    private void writeCorrelationFields(JsonGenerator json, Map<String, String> mdc, LogFieldNames names)
            throws IOException {
        if (mdc == null || mdc.isEmpty()) {
            return;
        }
        writeIfPresent(json, names.traceId(), mdc.get(config.traceIdMdcKey()));
        writeIfPresent(json, names.spanId(), mdc.get(config.spanIdMdcKey()));
        writeIfPresent(json, names.correlationId(), mdc.get(config.correlationIdMdcKey()));
    }

    private void writeServiceIdentity(JsonGenerator json, LogFieldNames names) throws IOException {
        ServiceIdentity identity = config.identity();
        writeIfPresent(json, names.serviceName(), identity.name());
        writeIfPresent(json, names.serviceNamespace(), identity.namespace());
        writeIfPresent(json, names.serviceVersion(), identity.version());
        writeIfPresent(json, names.serviceEnvironment(), identity.environment());
        writeIfPresent(json, names.serviceInstance(), identity.instance());
    }

    private void writeStaticFields(JsonGenerator json) throws IOException {
        for (Map.Entry<String, String> field : config.staticFields().entrySet()) {
            writeIfPresent(json, field.getKey(), field.getValue());
        }
    }

    /**
     * Writes SLF4J 2.x fluent key-value pairs as top-level fields.
     *
     * <p>{@code log.atInfo().addKeyValue("orderId", id).log("shipped")} is the modern way to attach
     * structured data to one statement without pushing it into the MDC, where it would then leak
     * onto every later line of that thread. Supporting it is what lets application code be
     * structured deliberately rather than by accident.
     */
    private void writeKeyValuePairs(JsonGenerator json, ILoggingEvent event) throws IOException {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null || pairs.isEmpty()) {
            return;
        }
        for (KeyValuePair pair : pairs) {
            if (pair.key == null) {
                continue;
            }
            String value = pair.value == null ? null : String.valueOf(pair.value);
            writeIfPresent(json, pair.key, maskIfSensitive(pair.key, value));
        }
    }

    private void writeMarkers(JsonGenerator json, ILoggingEvent event, LogFieldNames names) throws IOException {
        if (!config.includeMarkers()) {
            return;
        }
        List<Marker> markers = event.getMarkerList();
        if (markers == null || markers.isEmpty()) {
            return;
        }
        json.writeArrayFieldStart(names.markers());
        for (Marker marker : markers) {
            json.writeString(marker.getName());
        }
        json.writeEndArray();
    }

    /**
     * Writes the exception as three separate fields.
     *
     * <p>Split rather than folded into the message because the three are queried differently: the
     * type is a facet worth grouping and alerting on, the message is searched, and the stack trace
     * is only ever read once a specific line has been found. Appending the trace to {@code message}
     * - which a pattern layout does - makes the message field unusable for grouping, since every
     * occurrence differs by its frame addresses.
     */
    private void writeThrowable(JsonGenerator json, ILoggingEvent event, LogFieldNames names) throws IOException {
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable == null) {
            return;
        }
        writeIfPresent(json, names.errorType(), throwable.getClassName());
        writeIfPresent(json, names.errorMessage(), truncate(throwable.getMessage(), config.maxMessageLength()));
        // Logback's own renderer, so the text is byte-for-byte what a console appender would have
        // printed - including causes, suppressed exceptions and "... 42 common frames omitted".
        // Anyone who has learned to read one of these can read this one.
        writeIfPresent(json, names.stackTrace(),
                truncate(ThrowableProxyUtil.asString(throwable), config.maxStackTraceLength()));
    }

    /**
     * Writes the remaining MDC entries, filtered, masked, and by default nested.
     *
     * <p>Nesting under one object is the safe default because the MDC is an open map that
     * application code writes to freely. Flattened, an entry called {@code message} or
     * {@code service.name} silently overwrites the structural field of that name; worse, in
     * Elasticsearch a key whose value type differs from the mapped type poisons the index template
     * for the whole index, rejecting unrelated documents from unrelated services.
     */
    private void writeMdc(JsonGenerator json, Map<String, String> mdc) throws IOException {
        if (!config.includeMdc()) {
            return;
        }
        if (mdc == null || mdc.isEmpty()) {
            return;
        }

        boolean started = false;
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            String key = entry.getKey();
            if (key == null || isPromoted(key) || !isIncluded(key)) {
                continue;
            }
            String value = maskIfSensitive(key, entry.getValue());
            if (value == null) {
                continue;
            }
            if (config.nestMdc() && !started) {
                json.writeObjectFieldStart(config.mdcFieldName());
                started = true;
            }
            json.writeStringField(key, value);
        }
        if (started) {
            json.writeEndObject();
        }
    }

    /** True for the three ids already written as first-class fields. */
    private boolean isPromoted(String key) {
        return key.equals(config.traceIdMdcKey())
                || key.equals(config.spanIdMdcKey())
                || key.equals(config.correlationIdMdcKey());
    }

    private boolean isIncluded(String key) {
        List<String> include = config.mdcIncludeKeys();
        if (!include.isEmpty() && !include.contains(key)) {
            return false;
        }
        return !config.mdcExcludeKeys().contains(key);
    }

    /**
     * Replaces the value when the key looks sensitive.
     *
     * <p>Substring matching, so {@code userPassword} and {@code X-Api-Key} are both caught without
     * anybody having to enumerate the spellings a codebase actually uses. The bias is deliberately
     * towards over-masking: a masked field that did not need masking costs one debugging session,
     * and an unmasked credential in the log aggregator costs a rotation of every secret it touched.
     */
    private String maskIfSensitive(String key, String value) {
        if (value == null) {
            return null;
        }
        String lowerKey = key.toLowerCase(Locale.ROOT);
        for (String masked : config.maskedKeySubstrings()) {
            if (lowerKey.contains(masked)) {
                return Redaction.MASK;
            }
        }
        return value;
    }

    /** Cuts a value to {@code limit} characters, marking that it was cut. {@code 0} means no limit. */
    private String truncate(String value, int limit) {
        if (value == null || limit <= 0 || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + TRUNCATION_SUFFIX;
    }

    private void writeIfPresent(JsonGenerator json, String field, String value) throws IOException {
        if (field != null && value != null && !value.isEmpty()) {
            json.writeStringField(field, value);
        }
    }

    /**
     * The OpenTelemetry severity number for a Logback level.
     *
     * <p>Numeric severity is what lets a backend express "at least WARN" as a range query.
     * Comparing the text instead means enumerating level names, and getting the order wrong the
     * first time someone adds a custom one.
     */
    private int severityNumber(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> SEVERITY_ERROR;
            case Level.WARN_INT -> SEVERITY_WARN;
            case Level.INFO_INT -> SEVERITY_INFO;
            case Level.DEBUG_INT -> SEVERITY_DEBUG;
            case Level.TRACE_INT -> SEVERITY_TRACE;
            default -> SEVERITY_UNSPECIFIED;
        };
    }

    /** Exposed for the initializer's logging and for tests that assert on what was configured. */
    public JsonLogEncoderConfig config() {
        return config;
    }
}
