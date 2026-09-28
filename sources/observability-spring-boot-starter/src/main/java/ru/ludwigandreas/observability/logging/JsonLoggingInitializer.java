package ru.ludwigandreas.observability.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.spi.AppenderAttachable;
import java.util.Iterator;
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
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentityResolver;
import ru.ludwigandreas.observability.logging.json.JsonLogEncoder;
import ru.ludwigandreas.observability.logging.json.JsonLogEncoderConfig;
import ru.ludwigandreas.observability.logging.json.LogFieldNames;

/**
 * Swaps Logback's pattern encoders for {@link JsonLogEncoder} before the application logs anything
 * worth keeping.
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

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        ObservabilityProperties properties = Binder.get(environment)
                .bindOrCreate("ludwig.observability", ObservabilityProperties.class);

        if (!properties.isEnabled() || !properties.getLogging().getJson().isEnabled()) {
            return;
        }

        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (!(loggerFactory instanceof LoggerContext loggerContext)) {
            // Log4j2, or SLF4J bound to something else entirely. Not an error - the module simply has
            // nothing to offer there, and saying so once beats failing startup over a logging format.
            LoggerFactory.getLogger(JsonLoggingInitializer.class).info(
                    "Structured JSON logging is enabled but the SLF4J binding is not Logback ({}); "
                            + "leaving the logging configuration untouched",
                    loggerFactory.getClass().getName());
            return;
        }

        ServiceIdentity identity = ServiceIdentityResolver.resolve(
                environment, event.getSpringApplication().getMainApplicationClass());
        JsonLogEncoderConfig config = toEncoderConfig(properties, identity);

        int replaced = replaceEncoders(loggerContext, config);

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

    private JsonLogEncoderConfig toEncoderConfig(ObservabilityProperties properties, ServiceIdentity identity) {
        ObservabilityProperties.Logging.Json json = properties.getLogging().getJson();
        return new JsonLogEncoderConfig(
                LogFieldNames.of(json.getFieldSet()),
                identity,
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

    /** Walks every appender reachable from the root logger and converts the eligible ones. */
    private int replaceEncoders(LoggerContext loggerContext, JsonLogEncoderConfig config) {
        ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        return replaceEncoders(rootLogger.iteratorForAppenders(), loggerContext, config);
    }

    private int replaceEncoders(Iterator<Appender<ILoggingEvent>> appenders, LoggerContext loggerContext,
            JsonLogEncoderConfig config) {
        int replaced = 0;
        while (appenders.hasNext()) {
            Appender<ILoggingEvent> appender = appenders.next();
            if (appender instanceof OutputStreamAppender<ILoggingEvent> streamAppender
                    && isConvertible(streamAppender.getEncoder())) {
                JsonLogEncoder encoder = new JsonLogEncoder(config);
                encoder.setContext(loggerContext);
                encoder.start();
                streamAppender.setEncoder(encoder);
                replaced++;
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
                replaced += replaceEncoders(attachable.iteratorForAppenders(), loggerContext, config);
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
        // and so the conversion happens before the first application log line.
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }
}
