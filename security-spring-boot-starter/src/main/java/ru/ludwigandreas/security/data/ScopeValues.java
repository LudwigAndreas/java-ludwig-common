package ru.ludwigandreas.security.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the string values a scope carries into the type a column actually holds.
 *
 * <p>Scope values are always strings on the way in - they came from a token, a configuration file or a
 * grant row - and the column they are compared against rarely is. Every binding needs the same
 * conversion with the same failure rule, so it lives here rather than being repeated per binding type.
 */
final class ScopeValues {

    private static final Logger log = LoggerFactory.getLogger(ScopeValues.class);

    /**
     * Bounded set of already-reported values, so a single malformed grant row does not emit a WARN on
     * every request for as long as it exists. The cap matters more than the precision: an unbounded set
     * keyed on values that ultimately originate outside this service is a slow memory leak, and a
     * log line repeated at request rate is an outage of its own.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final int REPORTED_CAP = 512;

    private ScopeValues() {
    }

    /**
     * An unparseable value is dropped rather than propagated. It means a grant refers to something the
     * column cannot hold (a stale grant row, a typo in configuration), and the safe reading of "you may
     * see rows whose id is {@code not-a-uuid}" is that there are no such rows.
     *
     * @return the values that converted successfully; empty means the binding can never match, which
     *         callers must treat as a denial and never as an absent restriction
     */
    static <V> List<V> parse(Set<String> rawValues, Function<String, V> parser, Object path) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        List<V> parsed = new ArrayList<>(rawValues.size());
        for (String raw : rawValues) {
            try {
                V value = parser.apply(raw);
                if (value != null) {
                    parsed.add(value);
                }
            } catch (RuntimeException e) {
                report(raw, path, e);
            }
        }
        return Collections.unmodifiableList(parsed);
    }

    private static void report(String raw, Object path, RuntimeException cause) {
        String key = path + "|" + raw;
        boolean first = REPORTED.size() < REPORTED_CAP && REPORTED.add(key);
        if (first) {
            log.warn("Dropping unusable data-scope value '{}' for path {}: {}. "
                    + "Further occurrences of this value are logged at DEBUG.", raw, path, cause.toString());
        } else {
            log.debug("Dropping unusable data-scope value '{}' for path {}: {}", raw, path, cause.toString());
        }
    }
}
