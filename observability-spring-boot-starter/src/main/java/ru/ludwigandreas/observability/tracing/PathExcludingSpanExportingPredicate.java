package ru.ludwigandreas.observability.tracing;

import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import java.util.List;
import java.util.Map;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

/**
 * Drops server spans for paths that are not worth storing - health probes, above all.
 *
 * <h2>Why at export rather than at sampling</h2>
 *
 * <p>Refusing to sample a probe would be cheaper, but it would also be wrong in one case that
 * matters. The sampling decision is inherited by the whole trace, so a request that legitimately
 * passes through an excluded path on its way somewhere else - or a probe endpoint that a service has
 * deliberately made meaningful - would lose not only its own span but every downstream span beneath
 * it. Filtering at export discards exactly the spans matched and leaves the rest of the trace, and
 * the rest of the estate's traces, untouched.
 *
 * <p>The cost of that choice is honest: an excluded span is still created and still occupies its
 * slot in the batch processor's queue. It is bounded, it is per-request, and it buys correctness.
 *
 * <h2>Scope of this rule</h2>
 *
 * <p>The path is read from the span's tags, and only server-side instrumentation records it. A span
 * with no recognisable path tag - an internal span, a scheduled job, a Kafka consumer - is always
 * exported. That is the deliberate direction to fail in: a rule that cannot see a path must not
 * guess, because silently discarding spans is close to impossible to diagnose from the other end,
 * where the only symptom is a trace that is missing something nobody knows to look for.
 */
public class PathExcludingSpanExportingPredicate implements SpanExportingPredicate {

    /** Separates the scheme from the authority in an absolute URL; the path starts after it. */
    private static final String SCHEME_SEPARATOR = "://";

    /**
     * Tag keys a path may appear under, in priority order.
     *
     * <p>Three of them because the naming changed underneath the ecosystem: Micrometer's own HTTP
     * server convention writes {@code uri}, the stable OpenTelemetry HTTP semantic conventions write
     * {@code http.route}, and the superseded ones wrote {@code http.url}. Checking all three means
     * the exclusion keeps working across a semconv migration instead of quietly matching nothing.
     */
    private static final List<String> PATH_TAG_KEYS = List.of("uri", "http.route", "http.url", "url.path");

    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> excludedPatterns;

    public PathExcludingSpanExportingPredicate(List<String> excludedPatterns) {
        this.excludedPatterns = List.copyOf(excludedPatterns);
    }

    @Override
    public boolean isExportable(FinishedSpan span) {
        if (excludedPatterns.isEmpty()) {
            return true;
        }
        String path = pathOf(span);
        if (path == null) {
            return true;
        }
        for (String pattern : excludedPatterns) {
            if (pathMatcher.match(pattern, path)) {
                return false;
            }
        }
        return true;
    }

    private String pathOf(FinishedSpan span) {
        Map<String, String> tags = span.getTags();
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        for (String key : PATH_TAG_KEYS) {
            String value = tags.get(key);
            if (value != null && !value.isBlank()) {
                return toPath(value);
            }
        }
        return null;
    }

    /**
     * Reduces a value that may be a full URL to the path the patterns are written against.
     *
     * <p>{@code http.url} carries scheme and authority, so matching {@code /actuator/**} against it
     * verbatim never succeeds - the exclusion would appear configured and do nothing.
     */
    private String toPath(String value) {
        int schemeEnd = value.indexOf(SCHEME_SEPARATOR);
        if (schemeEnd < 0) {
            return stripQuery(value);
        }
        int pathStart = value.indexOf('/', schemeEnd + SCHEME_SEPARATOR.length());
        return pathStart < 0 ? "/" : stripQuery(value.substring(pathStart));
    }

    private String stripQuery(String value) {
        int queryStart = value.indexOf('?');
        return queryStart < 0 ? value : value.substring(0, queryStart);
    }
}
