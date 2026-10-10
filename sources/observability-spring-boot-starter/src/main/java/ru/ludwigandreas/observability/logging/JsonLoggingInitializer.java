package ru.ludwigandreas.observability.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.spi.AppenderAttachable;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.function.Function;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.core.BuildIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentityResolver;
import ru.ludwigandreas.observability.logging.json.JsonLogEncoder;
import ru.ludwigandreas.observability.logging.json.JsonLogEncoderConfig;
import ru.ludwigandreas.observability.logging.json.LogFieldNames;

/**
 * Installs the platform's log format - {@link JsonLogEncoder}, or the human-readable pattern - on
 * Logback's pattern encoders before the application logs anything worth keeping.
 *
 * <h2>Two formats, one set of fields</h2>
 *
 * <p>Which format is installed is {@code ludwig.observability.logging.json.enabled}, whose default
 * {@code ObservabilityEnvironmentPostProcessor} makes profile-dependent: structured everywhere, text
 * under {@code local}. The choice is about readability and nothing else. The text pattern carries
 * the service name, version, environment, instance and abbreviated commit, and the correlation,
 * trace and span ids - the same identity and join keys the JSON carries - so switching format never
 * also means losing the metadata that makes a line findable. Leaving Spring Boot's default pattern
 * in place, which is what "JSON off" used to mean, lost all of it.
 *
 * <p>The text pattern and the three JSON field sets are therefore four statements of which fields
 * are mandatory, and nothing structural keeps them in step. {@code MandatoryLogFieldsTest} does: it
 * renders one event through all four and fails when any of them drops a field.
 *
 * <h2>Why an early listener rather than autoconfiguration</h2>
 *
 * <p>Timing is the entire reason this is not a bean. By the time autoconfiguration runs, a service
 * has already logged its banner, its active profiles, its Liquibase migrations and any failure that
 * happened during early bootstrap - and those are the lines an operator most wants when a pod is
 * crash-looping before it ever reaches a healthy state. Switching format afterwards would leave a
 * stream that is half text and half JSON, which most log shippers handle by dropping the half they
 * were not configured for.
 *
 * <p>So this runs at {@link ApplicationEnvironmentPreparedEvent}, ordered one step after
 * {@link LoggingApplicationListener#DEFAULT_ORDER} - that is, immediately after Spring Boot has
 * initialized the logging system and therefore as early as there is anything to reconfigure.
 *
 * <h2>What it will not touch</h2>
 *
 * <p>Only encoders that are Logback's own layout-wrapping kind - the pattern encoder a default
 * configuration installs - are replaced. An appender carrying any other encoder was configured
 * deliberately by the service, in its own {@code logback-spring.xml}, and is left exactly as it is.
 * A starter that overrode that would be overriding a decision someone made on purpose, in the one
 * area where a surprise is hardest to notice: the output still appears, in the wrong shape,
 * somewhere else.
 */
public class JsonLoggingInitializer implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /**
     * Micrometer Tracing's SLF4J bridge publishes the trace and span ids under these fixed MDC keys.
     * They are not configurable on the bridge's side, so they are not configurable here either -
     * making them a knob would only allow the encoder to be pointed at keys nothing ever writes.
     */
    private static final String TRACE_ID_MDC_KEY = "traceId";
    private static final String SPAN_ID_MDC_KEY = "spanId";

    /**
     * Spring Boot's own properties for a hand-chosen pattern. A service that set one has decided
     * what its text output looks like, and the text pattern is not installed over that decision.
     */
    private static final String CONSOLE_PATTERN_PROPERTY = "logging.pattern.console";
    private static final String FILE_PATTERN_PROPERTY = "logging.pattern.file";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        ObservabilityProperties properties = Binder.get(environment)
                .bindOrCreate("ludwig.observability", ObservabilityProperties.class);

        if (!properties.isEnabled()) {
            return;
        }
        boolean json = properties.getLogging().getJson().isEnabled();

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (!(loggerFactory instanceof LoggerContext loggerContext)) {
            // Log4j2, or SLF4J bound to something else entirely. Not an error - the module simply has
            // nothing to offer there, and saying so once beats failing startup over a logging format.
            LoggerFactory.getLogger(JsonLoggingInitializer.class).info(
                    "The SLF4J binding is not Logback ({}); leaving the logging configuration untouched",
                    loggerFactory.getClass().getName());
            return;
        }

        ServiceIdentity identity = ServiceIdentityResolver.resolve(
                environment, event.getSpringApplication().getMainApplicationClass());
        // Bound, not resolved again: the post-processor already read the provenance resources and
        // published the result, and the startup identity event reads the same properties. One
        // resolution, so the commit on every line cannot differ from the commit on that event.
        BuildIdentity build = properties.getBuild().toIdentity();

        if (!json) {
            installTextPattern(loggerContext, environment, textPattern(properties, identity, build));
            return;
        }

        JsonLogEncoderConfig config = toEncoderConfig(properties, identity, build);
        int replaced = replaceEncoders(loggerContext, appender -> {
            JsonLogEncoder encoder = new JsonLogEncoder(config);
            encoder.setContext(loggerContext);
            encoder.start();
            return encoder;
        });

        Logger log = LoggerFactory.getLogger(JsonLoggingInitializer.class);
        if (replaced == 0) {
            // Worth a warning: the service asked for JSON logs and is not getting them. Silence here
            // is how an estate discovers at query time that one service never shipped structured logs.
            log.warn("Structured JSON logging is enabled but no pattern-encoded appender was found to "
                    + "convert; if this service defines its own logback configuration, attach {} there",
                    JsonLogEncoder.class.getName());
        } else {
            log.debug("Structured JSON logging enabled on {} appender(s) using the {} field set",
                    replaced, properties.getLogging().getJson().getFieldSet());
        }
    }

    /**
     * Replaces the pattern on every eligible appender with the platform's text pattern.
     *
     * <p>Same eligibility rule as the JSON path - only Logback's own layout-wrapping encoders - with
     * one more deference: an appender whose pattern the service chose through Spring Boot's
     * {@code logging.pattern.console} or {@code logging.pattern.file} is left alone, for the reason
     * a hand-configured appender is. Nothing is logged when no appender is converted; the output is
     * still text either way, so there is no wrong-format stream to warn about.
     */
    private void installTextPattern(LoggerContext loggerContext, ConfigurableEnvironment environment,
            String pattern) {
        replaceEncoders(loggerContext, appender -> {
            String ownPattern = appender instanceof ConsoleAppender ? CONSOLE_PATTERN_PROPERTY : FILE_PATTERN_PROPERTY;
            if (environment.containsProperty(ownPattern)) {
                return null;
            }
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(loggerContext);
            encoder.setCharset(StandardCharsets.UTF_8);
            encoder.setPattern(pattern);
            encoder.start();
            return encoder;
        });
    }

    /**
     * The human-readable pattern, built once from the resolved identity.
     *
     * <p>The identity is written into the pattern as literal text, because it is a constant of the
     * process and there is nothing to look up per event. The correlation, trace and span ids are
     * {@code %mdc} conversions, because they differ per event and the MDC is where they arrive. A
     * custom conversion word would be tidier and would need a Logback configuration file to
     * register it, which is the thing a service is not supposed to need.
     *
     * <p>The shape is {@code [name/version@commit env=... instance=...] [correlation,trace,span]}:
     * an identity part that is absent is left out entirely, never written as a placeholder, while
     * the three ids keep their positions so an unsampled request reads as {@code [corr-1,,]}.
     */
    private static String textPattern(ObservabilityProperties properties, ServiceIdentity identity,
            BuildIdentity build) {
        StringBuilder who = new StringBuilder();
        append(who, "", identity.name());
        append(who, "/", identity.version());
        append(who, "@", build.abbreviatedCommitId());
        append(who, " env=", identity.environment());
        append(who, " instance=", identity.instance());

        StringBuilder pattern = new StringBuilder("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5level ");
        if (!who.isEmpty()) {
            pattern.append('[').append(who.toString().strip()).append("] ");
        }
        pattern.append("[%mdc{").append(properties.getCorrelation().getMdcKey()).append(":-},")
                .append("%mdc{").append(TRACE_ID_MDC_KEY).append(":-},")
                .append("%mdc{").append(SPAN_ID_MDC_KEY).append(":-}] ")
                .append("[%thread] %logger{39} : %msg%n");
        return pattern.toString();
    }

    private static void append(StringBuilder target, String prefix, String value) {
        if (value != null) {
            target.append(prefix).append(escapeLiteral(value));
        }
    }

    /**
     * Makes a configured value safe to embed in a Logback pattern as literal text.
     *
     * <p>A service name is somebody's configuration. Left raw, a {@code %} in it would be read as
     * the start of a conversion word and a parenthesis as a grouping, and the pattern would fail to
     * compile at startup - over a logging format.
     */
    private static String escapeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("(", "\\(").replace(")", "\\)");
    }

    private JsonLogEncoderConfig toEncoderConfig(ObservabilityProperties properties, ServiceIdentity identity,
            BuildIdentity build) {
        ObservabilityProperties.Logging.Json json = properties.getLogging().getJson();
        return new JsonLogEncoderConfig(
                LogFieldNames.of(json.getFieldSet()),
                identity,
                build,
                json.getStaticFields(),
                json.isIncludeMdc(),
                json.getMdcIncludeKeys(),
                json.getMdcExcludeKeys(),
                json.isNestMdc(),
                json.getMdcFieldName(),
                json.getMaskedKeys(),
                TRACE_ID_MDC_KEY,
                SPAN_ID_MDC_KEY,
                properties.getCorrelation().getMdcKey(),
                json.getMaxMessageLength(),
                json.getMaxStackTraceLength(),
                json.isIncludeThreadName(),
                json.isIncludeMarkers(),
                json.isIncludeMessageTemplate());
    }

    /**
     * Walks every appender reachable from the root logger and converts the eligible ones.
     *
     * @param replacement builds the encoder for one eligible appender, or returns {@code null} to
     *                    leave that appender as it is
     */
    private int replaceEncoders(LoggerContext loggerContext,
            Function<OutputStreamAppender<ILoggingEvent>, Encoder<ILoggingEvent>> replacement) {
        ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        return replaceEncoders(rootLogger.iteratorForAppenders(), replacement);
    }

    private int replaceEncoders(Iterator<Appender<ILoggingEvent>> appenders,
            Function<OutputStreamAppender<ILoggingEvent>, Encoder<ILoggingEvent>> replacement) {
        int replaced = 0;
        while (appenders.hasNext()) {
            Appender<ILoggingEvent> appender = appenders.next();
            if (appender instanceof OutputStreamAppender<ILoggingEvent> streamAppender
                    && isConvertible(streamAppender.getEncoder())) {
                Encoder<ILoggingEvent> encoder = replacement.apply(streamAppender);
                if (encoder != null) {
                    streamAppender.setEncoder(encoder);
                    replaced++;
                }
            }
            // AsyncAppender and friends hold the real appenders inside them. Without this the common
            // production setup - an async wrapper in front of a file appender - would silently keep
            // its pattern layout while the swap reported success on nothing.
            if (appender instanceof AppenderAttachable<?>) {
                // Unchecked, and safe: this iterator was reached from a Logger<ILoggingEvent>, so
                // every appender attached anywhere beneath it handles ILoggingEvent. Logback's own
                // API cannot express that, because AppenderAttachable is not a subtype of Appender
                // and the compiler has no path between the two type arguments.
                @SuppressWarnings("unchecked")
                AppenderAttachable<ILoggingEvent> attachable = (AppenderAttachable<ILoggingEvent>) appender;
                replaced += replaceEncoders(attachable.iteratorForAppenders(), replacement);
            }
        }
        return replaced;
    }

    /**
     * Only Logback's layout-wrapping encoders are ours to replace; see the class javadoc for why
     * anything else is left alone.
     */
    private boolean isConvertible(Encoder<ILoggingEvent> encoder) {
        return encoder instanceof LayoutWrappingEncoder;
    }

    @Override
    public int getOrder() {
        // One step after the logging system has been initialized, so there is something to convert
        // and so the conversion happens before the first application log line - in either format.
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }
}
