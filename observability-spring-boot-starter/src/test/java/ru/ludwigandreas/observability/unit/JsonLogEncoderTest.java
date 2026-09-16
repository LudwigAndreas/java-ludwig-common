package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MarkerFactory;
import org.slf4j.event.KeyValuePair;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.logging.json.JsonLogEncoder;
import ru.ludwigandreas.observability.logging.json.JsonLogEncoderConfig;
import ru.ludwigandreas.observability.logging.json.LogFieldNames;

/** What one log line actually looks like once it reaches the aggregator. */
class JsonLogEncoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ServiceIdentity IDENTITY =
            new ServiceIdentity("catalog", "commerce", "1.4.2", "prod", "catalog-7d9f-xk2");

    @Test
    void writesOneJsonObjectPerLineWithTheCoreFields() throws Exception {
        JsonNode line = encode(config(LogFieldNames.ecs()), event(Level.INFO, "order {} shipped", "4711"));

        assertThat(line.get("log.level").asText()).isEqualTo("INFO");
        assertThat(line.get("log.logger").asText()).isEqualTo("com.example.OrderService");
        assertThat(line.get("message").asText()).isEqualTo("order 4711 shipped");
        assertThat(line.get("service.name").asText()).isEqualTo("catalog");
        assertThat(line.get("service.version").asText()).isEqualTo("1.4.2");
        assertThat(line.get("service.environment").asText()).isEqualTo("prod");
    }

    @Test
    void emitsFixedMillisecondPrecisionSoShippersCanInferOneTimestampFormat() throws Exception {
        LoggingEvent onExactSecond = event(Level.INFO, "tick");
        onExactSecond.setTimeStamp(1_700_000_000_000L);

        JsonNode line = encode(config(LogFieldNames.ecs()), onExactSecond);

        // The point of the assertion is the ".000Z": ISO_INSTANT would have rendered this as
        // "...:20Z" and a shipper that learned the format from these lines would reject the rest.
        assertThat(line.get("@timestamp").asText()).endsWith(".000Z").hasSize(24);
    }

    @Test
    void promotesTraceSpanAndCorrelationIdsOutOfTheMdcIntoTheirOwnFields() throws Exception {
        LoggingEvent event = eventWithMdc(Map.of(
                "traceId", "0af7651916cd43dd8448eb211c80319c",
                "spanId", "b7ad6b7169203331",
                "correlationId", "corr-1",
                "tenant", "acme"), "handling");

        JsonNode line = encode(config(LogFieldNames.ecs()), event);

        assertThat(line.get("trace.id").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(line.get("span.id").asText()).isEqualTo("b7ad6b7169203331");
        assertThat(line.get("correlation.id").asText()).isEqualTo("corr-1");
        // Promoted ids must not also appear inside the MDC object, or every line carries each id twice.
        assertThat(line.get("labels").properties()).extracting(Map.Entry::getKey).containsExactly("tenant");
    }

    @Test
    void nestsMdcSoAnApplicationKeyCannotOverwriteAStructuralField() throws Exception {
        LoggingEvent event = eventWithMdc(Map.of("message", "mdc value", "service.name", "impostor"),
                "real message");

        JsonNode line = encode(config(LogFieldNames.ecs()), event);

        assertThat(line.get("message").asText()).isEqualTo("real message");
        assertThat(line.get("service.name").asText()).isEqualTo("catalog");
        assertThat(line.get("labels").get("message").asText()).isEqualTo("mdc value");
    }

    @Test
    void masksValuesWhoseKeyLooksSensitive() throws Exception {
        LoggingEvent event = eventWithMdc(
                Map.of("userPassword", "hunter2", "X-Api-Key", "abc", "tenant", "acme"), "authenticating");

        JsonNode labels = encode(config(LogFieldNames.ecs()), event).get("labels");

        assertThat(labels.get("userPassword").asText()).isEqualTo("***");
        assertThat(labels.get("X-Api-Key").asText()).isEqualTo("***");
        assertThat(labels.get("tenant").asText()).isEqualTo("acme");
    }

    @Test
    void masksStructuredArgumentsTooNotOnlyMdcEntries() throws Exception {
        LoggingEvent event = event(Level.INFO, "issuing");
        event.setKeyValuePairs(List.of(new KeyValuePair("accessToken", "secret-value"),
                new KeyValuePair("orderId", "4711")));

        JsonNode line = encode(config(LogFieldNames.ecs()), event);

        assertThat(line.get("accessToken").asText()).isEqualTo("***");
        assertThat(line.get("orderId").asText()).isEqualTo("4711");
    }

    @Test
    void splitsAnExceptionIntoTypeMessageAndStackTrace() throws Exception {
        LoggingEvent event = event(Level.ERROR, "payment failed");
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("gateway refused")));

        JsonNode line = encode(config(LogFieldNames.ecs()), event);

        assertThat(line.get("error.type").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(line.get("error.message").asText()).isEqualTo("gateway refused");
        assertThat(line.get("error.stack_trace").asText()).contains("java.lang.IllegalStateException");
        // The message field stays groupable - the trace must not be appended to it.
        assertThat(line.get("message").asText()).isEqualTo("payment failed");
    }

    @Test
    void truncatesAnOversizedMessageAndSaysSo() throws Exception {
        JsonLogEncoderConfig config = configBuilder(LogFieldNames.ecs()).maxMessageLength(20).build();

        JsonNode line = encode(config, event(Level.INFO, "x".repeat(500)));

        assertThat(line.get("message").asText()).hasSize(20 + "...[truncated]".length())
                .endsWith("...[truncated]");
    }

    @Test
    void appliesTheConfiguredFieldNameScheme() throws Exception {
        JsonNode otel = encode(config(LogFieldNames.otel()), event(Level.WARN, "careful"));

        assertThat(otel.get("SeverityText").asText()).isEqualTo("WARN");
        assertThat(otel.get("SeverityNumber").asInt()).isEqualTo(13);
        assertThat(otel.get("Body").asText()).isEqualTo("careful");

        JsonNode flat = encode(config(LogFieldNames.flat()), event(Level.WARN, "careful"));

        assertThat(flat.get("level").asText()).isEqualTo("WARN");
        assertThat(flat.has("SeverityNumber")).isFalse();
        assertThat(flat.get("message").asText()).isEqualTo("careful");
    }

    @Test
    void writesMarkersAndStaticFields() throws Exception {
        JsonLogEncoderConfig config = configBuilder(LogFieldNames.ecs())
                .staticFields(Map.of("cluster", "eu-west-1a")).build();
        LoggingEvent event = event(Level.INFO, "audited");
        event.addMarker(MarkerFactory.getMarker("AUDIT"));

        JsonNode line = encode(config, event);

        assertThat(line.get("tags").get(0).asText()).isEqualTo("AUDIT");
        assertThat(line.get("cluster").asText()).isEqualTo("eu-west-1a");
    }

    @Test
    void emitsExactlyOneNewlineTerminatedLine() {
        String encoded = new String(
                new JsonLogEncoder(config(LogFieldNames.ecs())).encode(event(Level.INFO, "one line")),
                StandardCharsets.UTF_8);

        assertThat(encoded).endsWith("\n");
        assertThat(encoded.chars().filter(c -> c == '\n').count()).isEqualTo(1);
    }

    private JsonNode encode(JsonLogEncoderConfig config, LoggingEvent event) throws Exception {
        return MAPPER.readTree(new JsonLogEncoder(config).encode(event));
    }

    private LoggingEvent event(Level level, String message, Object... arguments) {
        return event(level, Map.of(), message, arguments);
    }

    /**
     * The MDC is supplied at construction because Logback permits {@code setMDCPropertyMap} exactly
     * once per event, and setting it here also stops the event resolving an MDC adapter through a
     * logger context that a hand-built event has no reason to have.
     */
    private LoggingEvent event(Level level, Map<String, String> mdc, String message, Object... arguments) {
        LoggingEvent event = new LoggingEvent();
        event.setMDCPropertyMap(mdc);
        event.setLoggerName("com.example.OrderService");
        event.setLevel(level);
        event.setMessage(message);
        event.setArgumentArray(arguments);
        event.setThreadName("http-nio-8080-exec-1");
        event.setTimeStamp(1_700_000_000_123L);
        return event;
    }

    private LoggingEvent eventWithMdc(Map<String, String> mdc, String message) {
        return event(Level.INFO, mdc, message);
    }

    private JsonLogEncoderConfig config(LogFieldNames names) {
        return configBuilder(names).build();
    }

    private ConfigBuilder configBuilder(LogFieldNames names) {
        return new ConfigBuilder(names);
    }

    /**
     * Assembles the seventeen-component config record without every test restating the defaults it
     * does not care about.
     */
    private static final class ConfigBuilder {

        private final LogFieldNames names;
        private Map<String, String> staticFields = Map.of();
        private int maxMessageLength = 16384;

        private ConfigBuilder(LogFieldNames names) {
            this.names = names;
        }

        private ConfigBuilder staticFields(Map<String, String> staticFields) {
            this.staticFields = staticFields;
            return this;
        }

        private ConfigBuilder maxMessageLength(int maxMessageLength) {
            this.maxMessageLength = maxMessageLength;
            return this;
        }

        private JsonLogEncoderConfig build() {
            ObservabilityProperties.Logging.Json defaults = new ObservabilityProperties.Logging.Json();
            return new JsonLogEncoderConfig(names, IDENTITY, staticFields, true, List.of(), List.of(),
                    true, "labels", defaults.getMaskedKeys(), "traceId", "spanId", "correlationId",
                    maxMessageLength, 12288, true, true, false);
        }
    }
}
