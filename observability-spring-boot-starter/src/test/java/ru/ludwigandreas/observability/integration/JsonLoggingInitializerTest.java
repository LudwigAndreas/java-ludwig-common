package ru.ludwigandreas.observability.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.logging.json.JsonLogEncoder;

/**
 * The end-to-end check that structured logging is actually installed.
 *
 * <p>Every other logging test exercises the encoder directly. This one starts a real
 * {@code SpringApplication}, because the encoder being correct is worth nothing if the initializer
 * never reaches the appender - and that failure mode is completely silent: the service starts, logs
 * happily in the wrong format, and nobody notices until a query returns nothing weeks later.
 */
class JsonLoggingInitializerTest {

    private final Map<OutputStreamAppender<ILoggingEvent>, Encoder<ILoggingEvent>> originalEncoders = new HashMap<>();

    /**
     * The initializer reconfigures the JVM's real Logback context, which is shared with every other
     * test in the module. Capturing the encoders here and restoring them afterwards keeps this test
     * from turning the rest of the suite's output into JSON.
     */
    @BeforeEach
    void captureEncoders() {
        appenders().forEach(appender -> originalEncoders.put(appender, appender.getEncoder()));
    }

    @AfterEach
    void restoreEncoders() {
        originalEncoders.forEach(OutputStreamAppender::setEncoder);
        originalEncoders.clear();
    }

    @Test
    void replacesThePatternEncoderWithTheJsonEncoder() {
        try (ConfigurableApplicationContext context = run("ludwig.observability.logging.json.enabled=true")) {
            assertThat(jsonEncoders()).isNotEmpty();
        }
    }

    @Test
    void stampsTheResolvedServiceIdentityOntoEveryLine() {
        try (ConfigurableApplicationContext context = run(
                "ludwig.observability.logging.json.enabled=true",
                "spring.application.name=catalog",
                "ludwig.observability.service.environment=prod",
                "ludwig.observability.service.namespace=commerce")) {

            JsonLogEncoder encoder = jsonEncoders().get(0);

            assertThat(encoder.config().identity().name()).isEqualTo("catalog");
            assertThat(encoder.config().identity().environment()).isEqualTo("prod");
            assertThat(encoder.config().identity().namespace()).isEqualTo("commerce");
        }
    }

    @Test
    void bindsTheNestedConfigurationRatherThanSilentlyFallingBackToDefaults() {
        // The initializer binds ObservabilityProperties by hand, outside the container, long before
        // @ConfigurationProperties would. If that binding quietly produced defaults, every knob under
        // ludwig.observability.logging.json would be ignored with nothing to show for it.
        try (ConfigurableApplicationContext context = run(
                "ludwig.observability.logging.json.enabled=true",
                "ludwig.observability.logging.json.field-set=flat",
                "ludwig.observability.logging.json.nest-mdc=false",
                "ludwig.observability.logging.json.max-message-length=42",
                "ludwig.observability.correlation.mdc-key=requestId")) {

            JsonLogEncoder encoder = jsonEncoders().get(0);

            assertThat(encoder.config().fieldNames().level()).isEqualTo("level");
            assertThat(encoder.config().nestMdc()).isFalse();
            assertThat(encoder.config().maxMessageLength()).isEqualTo(42);
            assertThat(encoder.config().correlationIdMdcKey()).isEqualTo("requestId");
        }
    }

    @Test
    void bindsListAndMapValuedPropertiesToo() {
        try (ConfigurableApplicationContext context = run(
                "ludwig.observability.logging.json.enabled=true",
                "ludwig.observability.logging.json.masked-keys[0]=ssn",
                "ludwig.observability.logging.json.static-fields.cluster=eu-west-1a")) {

            JsonLogEncoder encoder = jsonEncoders().get(0);

            assertThat(encoder.config().maskedKeySubstrings()).containsExactly("ssn");
            assertThat(encoder.config().staticFields()).containsEntry("cluster", "eu-west-1a");
        }
    }

    @Test
    void leavesLogbackAloneWhenJsonLoggingIsSwitchedOff() {
        try (ConfigurableApplicationContext context = run("ludwig.observability.logging.json.enabled=false")) {
            assertThat(jsonEncoders()).isEmpty();
            assertThat(appenders()).allSatisfy(appender ->
                    assertThat(appender.getEncoder()).isInstanceOf(LayoutWrappingEncoder.class));
        }
    }

    @Test
    void leavesLogbackAloneWhenTheWholeModuleIsDisabled() {
        try (ConfigurableApplicationContext context = run("ludwig.observability.enabled=false")) {
            assertThat(jsonEncoders()).isEmpty();
        }
    }

    @Test
    void defaultsToStructuredLoggingWithNoConfigurationAtAll() {
        // The starter's promise: add the dependency, get queryable logs. If this ever needs a switch,
        // every service in the estate will forget to set it.
        try (ConfigurableApplicationContext context = run()) {
            assertThat(new ObservabilityProperties().getLogging().getJson().isEnabled()).isTrue();
            assertThat(jsonEncoders()).isNotEmpty();
        }
    }

    private ConfigurableApplicationContext run(String... properties) {
        List<String> args = new ArrayList<>(List.of("--spring.main.banner-mode=off"));
        for (String property : properties) {
            args.add("--" + property);
        }
        return new SpringApplicationBuilder(EmptyApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private List<JsonLogEncoder> jsonEncoders() {
        return appenders().stream()
                .map(OutputStreamAppender::getEncoder)
                .filter(JsonLogEncoder.class::isInstance)
                .map(JsonLogEncoder.class::cast)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<OutputStreamAppender<ILoggingEvent>> appenders() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        List<OutputStreamAppender<ILoggingEvent>> found = new ArrayList<>();
        Iterator<Appender<ILoggingEvent>> iterator =
                loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> appender = iterator.next();
            if (appender instanceof OutputStreamAppender) {
                found.add((OutputStreamAppender<ILoggingEvent>) appender);
            }
        }
        return found;
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyApplication {
    }
}
