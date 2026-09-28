package ru.ludwigandreas.jira.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered, repeatable query string under construction.
 *
 * <p>Ordered because a reproducible URL is what makes a recorded HTTP fixture, a cache key and a log line
 * comparable between runs; repeatable because Jira uses repeated parameters in several places
 * ({@code ?fields=summary&fields=status}, {@code ?expand=...}) rather than comma-separated values, and a
 * plain {@code Map<String, String>} silently drops the second one.
 *
 * <p>Every {@code add} overload ignores {@code null} values. That is deliberate: it lets an API method
 * write {@code params.add("expand", expand)} for an optional argument instead of guarding each one, and it
 * means an unset option never reaches the wire as the literal text {@code null} - which Jira would reject
 * or, worse, interpret.
 */
public final class QueryParams {

    private final Map<String, List<String>> values = new LinkedHashMap<>();

    private QueryParams() {
    }

    /** A new, empty parameter set. */
    public static QueryParams of() {
        return new QueryParams();
    }

    /** A new parameter set holding a single name/value pair, skipped when the value is {@code null}. */
    public static QueryParams of(String name, Object value) {
        return of().add(name, value);
    }

    /** Adds one value, doing nothing when it is {@code null}. */
    public QueryParams add(String name, Object value) {
        if (value != null) {
            values.computeIfAbsent(name, key -> new ArrayList<>()).add(String.valueOf(value));
        }
        return this;
    }

    /**
     * Adds one entry per element, which is how Jira expects {@code fields} and {@code expand} on most
     * endpoints. A {@code null} or empty collection adds nothing.
     */
    public QueryParams addEach(String name, Collection<?> items) {
        if (items != null) {
            items.stream().filter(java.util.Objects::nonNull).forEach(item -> add(name, item));
        }
        return this;
    }

    /**
     * Adds the elements as a single comma-joined value, which is what the handful of endpoints that do not
     * accept repetition require. A {@code null} or empty collection adds nothing.
     */
    public QueryParams addJoined(String name, Collection<?> items) {
        if (items != null && !items.isEmpty()) {
            List<String> rendered = items.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
            if (!rendered.isEmpty()) {
                add(name, String.join(",", rendered));
            }
        }
        return this;
    }

    /** True when nothing has been added, in which case no {@code ?} should be appended to the path. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Renders the query string without a leading {@code ?}.
     *
     * <p>Percent-encoding is {@code application/x-www-form-urlencoded} with {@code +} rewritten back to
     * {@code %20}. The rewrite matters: {@code URLEncoder} emits {@code +} for a space, which is correct in
     * a form body and wrong in a URL path-query, where Jira reads it literally. A JQL query containing a
     * space would otherwise arrive with plus signs in it.
     */
    public String render() {
        StringBuilder out = new StringBuilder();
        values.forEach((name, list) -> list.forEach(value -> {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(encode(name)).append('=').append(encode(value));
        }));
        return out.toString();
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public String toString() {
        return render();
    }
}
