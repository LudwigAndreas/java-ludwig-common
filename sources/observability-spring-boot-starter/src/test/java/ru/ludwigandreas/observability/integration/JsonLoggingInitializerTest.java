package ru.ludwigandreas.observability.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.logging.JsonLoggingInitializer;
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
    void stampsTheResolvedCommitOntoEveryLine() {
        try (ConfigurableApplicationContext context = run(
                "ludwig.observability.logging.json.enabled=true",
                "ludwig.observability.build.abbreviated-commit-id=c1fc5b8")) {

            assertThat(jsonEncoders().get(0).config().build().abbreviatedCommitId()).isEqualTo("c1fc5b8");
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
    void installsTheHumanReadablePatternWhenJsonLoggingIsSwitchedOff() {
        try (ConfigurableApplicationContext context = run(
                "ludwig.observability.logging.json.enabled=false",
                "spring.application.name=catalog",
                "ludwig.observability.build.abbreviated-commit-id=c1fc5b8")) {
            assertThat(jsonEncoders()).isEmpty();
            // Text, but not Spring Boot's default pattern: that one carries no identity and no
            // correlation id, so "JSON off" used to mean losing every piece of metadata as well.
            assertThat(appenders()).isNotEmpty().allSatisfy(appender ->
                    assertThat(appender.getEncoder()).isInstanceOfSatisfying(PatternLayoutEncoder.class, encoder ->
                            assertThat(encoder.getPattern())
                                    .contains("catalog", "@c1fc5b8")
                                    .contains("%mdc{correlationId:-}", "%mdc{traceId:-}", "%mdc{spanId:-}")));
        }
    }

    @Test
    void selectsTheHumanReadablePatternFromTheLocalProfileAlone() {
        // No logging property at all: the profile is the only input, as it is for a new service.
        try (ConfigurableApplicationContext context = run("spring.profiles.active=local")) {
            assertThat(jsonEncoders()).isEmpty();
            assertThat(appenders()).isNotEmpty().allSatisfy(appender ->
                    assertThat(appender.getEncoder()).isInstanceOf(PatternLayoutEncoder.class));
        }
    }

    @Test
    void honoursAnExplicitJsonSettingUnderTheLocalProfile() {
        try (ConfigurableApplicationContext context = run(
                "spring.profiles.active=local", "ludwig.observability.logging.json.enabled=true")) {
            assertThat(jsonEncoders()).isNotEmpty();
        }
    }

    @Test
    void leavesAPatternTheServiceChoseForItselfAlone() {
        try (ConfigurableApplicationContext context = run(
                "ludwig.observability.logging.json.enabled=false",
                "logging.pattern.console=%msg%n")) {
            assertThat(appenders()).isNotEmpty().allSatisfy(appender ->
                    assertThat(appender.getEncoder()).isInstanceOfSatisfying(PatternLayoutEncoder.class, encoder ->
                            assertThat(encoder.getPattern()).doesNotContain("%mdc{")));
        }
    }

    @Test
    void survivesAServiceNameThatIsNotAValidPatternLiteral() {
        // A name is somebody's configuration. A % or a parenthesis in it must not stop the service
        // from starting, and must come out the way it went in.
        try (ConfigurableApplicationContext context = run(
                "ludwig.observability.logging.json.enabled=false",
                "ludwig.observability.service.name=catalog (eu) 100%")) {
            PatternLayoutEncoder encoder = (PatternLayoutEncoder) appenders().get(0).getEncoder();

            LoggingEvent event = new LoggingEvent();
            event.setLoggerName("com.example.OrderService");
            event.setLevel(Level.INFO);
            event.setMessage("tick");
            event.setTimeStamp(1_700_000_000_000L);
            event.setThreadName("main");
            event.setMDCPropertyMap(Map.of());

            assertThat(new String(encoder.encode(event), StandardCharsets.UTF_8)).contains("[catalog (eu) 100%");
        }
    }

    @Test
    void leavesAHandConfiguredEncoderAloneInEitherFormat() {
        // An appender whose encoder is not Logback's layout-wrapping kind was configured on purpose
        // by the service. Run against the live context directly, because a SpringApplication resets
        // Logback on the way in and would discard the appender before the initializer saw it.
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Encoder<ILoggingEvent> handConfigured = new HandConfiguredEncoder();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(loggerContext);
        appender.setName("hand-configured");
        appender.setEncoder(handConfigured);
        appender.setOutputStream(new ByteArrayOutputStream());
        ch.qos.logback.classic.Logger root = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            for (String json : List.of("true", "false")) {
                new JsonLoggingInitializer().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                        new DefaultBootstrapContext(), new SpringApplication(), new String[0],
                        new MockEnvironment().withProperty("ludwig.observability.logging.json.enabled", json)));

                assertThat(appender.getEncoder()).as("json.enabled=%s", json).isSameAs(handConfigured);
            }
        } finally {
            root.detachAppender(appender);
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

    /** Stands in for whatever encoder a service attached in its own logback configuration. */
    private static final class HandConfiguredEncoder extends EncoderBase<ILoggingEvent> {

        @Override
        public byte[] headerBytes() {
            return new byte[0];
        }

        @Override
        public byte[] encode(ILoggingEvent event) {
            return new byte[0];
        }

        @Override
        public byte[] footerBytes() {
            return new byte[0];
        }
    }
}
