package ru.ludwigandreas.webcore.problem;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.webcore.config.WebCoreProperties;
import ru.ludwigandreas.webcore.trace.TraceIdProvider;

/**
 * The one place that knows what a problem document looks like.
 *
 * <p>Every failure - a business exception, a mapped library exception, a Spring MVC exception, an
 * unhandled fault - arrives here as a {@link ProblemDefinition} and leaves as an RFC 9457
 * {@code ProblemDetail}. That funnel is the point: when each module rendered its own problems, the
 * same API answered with three different vocabularies (one advice set {@code title} and not
 * {@code code}, another the reverse), and a client had to special-case which subsystem had failed.
 *
 * <p>Members and where they come from:
 *
 * <ul>
 *   <li>{@code type} - {@code ludwig.web.problem.type-prefix} + the code, so it is stable and
 *       dereferenceable if the prefix points at documentation;
 *   <li>{@code title} - the {@code &lt;code&gt;.title} bundle key, falling back to the HTTP reason
 *       phrase;
 *   <li>{@code detail} - the {@code &lt;code&gt;} bundle key, formatted with the definition's
 *       arguments;
 *   <li>{@code status} - from {@link ProblemStatus};
 *   <li>{@code instance} - the request URI, when configured;
 *   <li>{@code code} - the machine-readable key a client branches on;
 *   <li>{@code traceId}, {@code timestamp} - when configured;
 *   <li>everything in {@link ProblemDefinition#properties()}.
 * </ul>
 */
public class ProblemDetailFactory {

    private final ProblemMessages messages;
    private final TraceIdProvider traceIdProvider;
    private final WebCoreProperties.Problem config;

    public ProblemDetailFactory(ProblemMessages messages, TraceIdProvider traceIdProvider,
                                WebCoreProperties.Problem config) {
        this.messages = messages;
        this.traceIdProvider = traceIdProvider == null ? TraceIdProvider.none() : traceIdProvider;
        this.config = config;
    }

    /** Renders {@code definition} in the current request's locale. */
    public ProblemDetail create(ProblemDefinition definition, String requestUri) {
        Locale locale = LocaleContextHolder.getLocale();
        HttpStatusCode status = HttpStatusCode.valueOf(definition.status().code());
        String code = definition.code();

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(config.getTypePrefix() + code));
        problem.setTitle(messages.get(code + ".title", null, reasonPhrase(status), locale));
        // The code as the last-resort detail rather than a generic sentence: an untranslated key is a
        // packaging bug, and seeing it in the response is how it gets noticed and fixed.
        problem.setDetail(messages.get(code, definition.args(), code, locale));
        problem.setProperty("code", code);

        if (config.isIncludeInstance() && requestUri != null && !requestUri.isBlank()) {
            problem.setInstance(URI.create(requestUri));
        }
        if (config.isIncludeTimestamp()) {
            problem.setProperty("timestamp", Instant.now().toString());
        }
        if (config.isIncludeTraceId()) {
            traceIdProvider.currentTraceId()
                    .ifPresent(traceId -> problem.setProperty(config.getTraceIdProperty(), traceId));
        }
        definition.properties().forEach((name, value) -> problem.setProperty(name, renderValue(value)));
        return problem;
    }

    /** Renders a {@link LocalizedException}, which already knows its own definition. */
    public ProblemDetail create(LocalizedException exception, String requestUri) {
        return create(exception.toDefinition(), requestUri);
    }

    /**
     * Adds the {@code violations} member to a validation problem.
     *
     * <p>Here rather than in the advice so that a service raising its own cross-field violations - a
     * domain rule that Bean Validation cannot express - reports them in the same shape as a rejected
     * {@code @NotBlank}.
     */
    public ProblemDetail withViolations(ProblemDetail problem, List<Violation> violations) {
        problem.setProperty("violations", renderValue(violations));
        return problem;
    }

    /**
     * Renders a property value, converting any {@link Violation} it finds.
     *
     * <p>Applied to every property rather than only to the {@code violations} member the advice sets
     * itself, so that a module contributing its own violations - a mapper for a bean-validation
     * exception, a domain rule that Bean Validation cannot express - gets the same shape and, more to
     * the point, cannot route around the {@code include-rejected-value} policy by attaching an
     * already-rendered map.
     */
    private Object renderValue(Object value) {
        if (value instanceof Violation violation) {
            return renderViolation(violation);
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(this::renderValue).toList();
        }
        return value;
    }

    private Map<String, Object> renderViolation(Violation violation) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("field", violation.field());
        rendered.put("message", violation.message());
        if (violation.code() != null) {
            rendered.put("code", violation.code());
        }
        if (config.isIncludeRejectedValue() && violation.rejectedValue() != null) {
            rendered.put("rejectedValue", violation.rejectedValue());
        }
        return rendered;
    }

    public WebCoreProperties.Problem config() {
        return config;
    }

    private String reasonPhrase(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }
}
