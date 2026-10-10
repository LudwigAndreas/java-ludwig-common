package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.observability.config.ObservabilityProperties.Logging.FieldSet;
import ru.ludwigandreas.observability.logging.JsonLoggingInitializer;
import ru.ludwigandreas.observability.logging.json.LogFieldNames;

/**
 * Every field the {@code service-log-stream} capability calls mandatory is on the event in all four
 * renderings: the three JSON field sets and the human-readable pattern.
 *
 * <p>This is the check the design makes load-bearing. The text pattern and the three field sets are
 * four separate statements of which fields a log event carries, and no architecture rule can see
 * that they agree - ArchUnit reads types, not the contents of a pattern string. {@link #MANDATORY}
 * is the one list; each rendering is held to all of it, so a field added to the capability is added
 * here once and fails for every rendering that does not carry it.
 *
 * <p>It goes through the real initializer rather than building an encoder by hand, because what
 * has to carry the fields is whatever the initializer actually installs.
 */
class MandatoryLogFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One mandatory field: what it is, the value this test gives it, and its name in a field set. */
    private record Mandatory(String what, String value, Function<LogFieldNames, String> jsonName) {
    }

    private static final List<Mandatory> MANDATORY = List.of(
            new Mandatory("service name", "catalog", LogFieldNames::serviceName),
            new Mandatory("application version", "1.4.2", LogFieldNames::serviceVersion),
            new Mandatory("abbreviated commit id", "c1fc5b8", LogFieldNames::commitId),
            new Mandatory("deployment environment", "prod", LogFieldNames::serviceEnvironment),
            new Mandatory("instance identifier", "catalog-7d9f-xk2", LogFieldNames::serviceInstance),
            new Mandatory("correlation id", "corr-1", LogFieldNames::correlationId),
            new Mandatory("trace id", "0af7651916cd43dd8448eb211c80319c", LogFieldNames::traceId),
            new Mandatory("span id", "b7ad6b7169203331", LogFieldNames::spanId));

    private final Map<OutputStreamAppender<ILoggingEvent>, Encoder<ILoggingEvent>> originalEncoders = new HashMap<>();
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private OutputStreamAppender<ILoggingEvent> capture;

    /**
     * The initializer reconfigures the JVM's real Logback context, which every other test shares. A
     * capturing appender is attached for the duration of one test, and every encoder the initializer
     * replaces on the appenders that were already there is put back afterwards.
     */
    @BeforeEach
    void attachCapturingAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootAppenders().forEach(appender -> originalEncoders.put(appender, appender.getEncoder()));

        PatternLayoutEncoder plain = new PatternLayoutEncoder();
        plain.setContext(context);
        plain.setPattern("%msg%n");
        plain.start();

        capture = new OutputStreamAppender<>();
        capture.setContext(context);
        capture.setName("mandatory-fields-capture");
        capture.setEncoder(plain);
        capture.setOutputStream(written);
        capture.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(capture);
    }

    @AfterEach
    void detachAndRestore() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(capture);
        capture.stop();
        originalEncoders.forEach(OutputStreamAppender::setEncoder);
        originalEncoders.clear();
        MDC.clear();
    }

    @ParameterizedTest
    @EnumSource(FieldSet.class)
    void carriesEveryMandatoryFieldInEachJsonFieldSet(FieldSet fieldSet) throws Exception {
        install(true, fieldSet);

        JsonNode line = MAPPER.readTree(logOneEvent());

        LogFieldNames names = LogFieldNames.of(fieldSet);
        for (Mandatory field : MANDATORY) {
            String name = field.jsonName().apply(names);
            assertThat(name).as("%s has a name in the %s field set", field.what(), fieldSet).isNotBlank();
            assertThat(line.path(name).asText(null))
                    .as("%s under '%s' in the %s field set", field.what(), name, fieldSet)
                    .isEqualTo(field.value());
        }
    }

    @Test
    void carriesEveryMandatoryFieldInTheHumanReadablePattern() {
        install(false, FieldSet.ECS);

        String line = logOneEvent();

        assertThat(line).doesNotStartWith("{");
        for (Mandatory field : MANDATORY) {
            assertThat(line).as("%s in the human-readable line", field.what()).contains(field.value());
        }
        assertThat(line).contains("order 4711 shipped");
    }

    @Test
    void omitsUnresolvedIdentityFromTheHumanReadableLineRatherThanFakingIt() {
        // An IDE run: a name, and nothing the build would have supplied.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ludwig.observability.logging.json.enabled", "false")
                .withProperty("ludwig.observability.service.name", "catalog");
        new JsonLoggingInitializer().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));

        String line = logOneEvent();

        assertThat(line).contains("[catalog").doesNotContain("@", "unknown", "null");
    }

    @Test
    void keepsTheCorrelationIdOnAnUnsampledRequestAndLeavesTheTraceSlotsEmpty() {
        install(false, FieldSet.ECS);
        MDC.clear();
        MDC.put("correlationId", "corr-2");

        LoggerFactory.getLogger("com.example.OrderService").info("unsampled");

        assertThat(written.toString(StandardCharsets.UTF_8)).contains("[corr-2,,]");
    }

    private void install(boolean json, FieldSet fieldSet) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ludwig.observability.logging.json.enabled", String.valueOf(json))
                .withProperty("ludwig.observability.logging.json.field-set", fieldSet.name().toLowerCase())
                .withProperty("ludwig.observability.service.name", "catalog")
                .withProperty("ludwig.observability.service.version", "1.4.2")
                .withProperty("ludwig.observability.service.environment", "prod")
                .withProperty("ludwig.observability.service.instance", "catalog-7d9f-xk2")
                .withProperty("ludwig.observability.build.abbreviated-commit-id", "c1fc5b8");
        new JsonLoggingInitializer().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));
        // The initializer may log about what it did; only the event under test should be read back.
        written.reset();
    }

    private String logOneEvent() {
        written.reset();
        MDC.put("correlationId", "corr-1");
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");
        LoggerFactory.getLogger("com.example.OrderService").info("order {} shipped", "4711");
        return written.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private List<OutputStreamAppender<ILoggingEvent>> rootAppenders() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Iterator<Appender<ILoggingEvent>> iterator =
                context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        List<OutputStreamAppender<ILoggingEvent>> found = new java.util.ArrayList<>();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> appender = iterator.next();
            if (appender instanceof OutputStreamAppender) {
                found.add((OutputStreamAppender<ILoggingEvent>) appender);
            }
        }
        return found;
    }
}
