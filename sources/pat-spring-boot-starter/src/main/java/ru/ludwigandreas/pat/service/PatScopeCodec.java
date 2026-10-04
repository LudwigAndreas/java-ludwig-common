package ru.ludwigandreas.pat.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Encodes a scope or audience set into the one column it is stored in, and back.
 *
 * <p>Exists so that exactly one place knows the encoding. The alternative - a {@code String.join} at each
 * write and a {@code split} at each read - is four copies of a format decision, and the failure mode is that
 * one of them handles an empty set or a stray space differently from the others, which surfaces as a token
 * whose scopes are subtly not what was issued.
 *
 * <p>Public, and used from both {@code service} and {@code web}. That is the point of having it: the
 * controller renders stored scopes into a response and the service writes them, and a second decoder in the
 * web layer would be the second copy this class exists to prevent.
 *
 * <p>Space-delimited rather than comma-delimited, matching OAuth's own convention for a scope list, which
 * is what the exchange endpoint speaks. A scope containing a space is therefore refused at encode time
 * rather than silently split on read - the opposite order would store something unreadable and discover it
 * on the next verification.
 */
public final class PatScopeCodec {

    private static final String DELIMITER = " ";

    private PatScopeCodec() {
    }

    /** Encodes a non-empty set. Refuses an element containing the delimiter. */
    public static String encode(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("a stored set must not be empty");
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.contains(DELIMITER)) {
                // Refused here rather than escaped. An escaping scheme is a second format decision and a
                // second thing to get wrong, and no legitimate scope or audience name contains a space.
                throw new IllegalArgumentException(
                        "a scope or audience must not contain a space: '" + trimmed + "'");
            }
            cleaned.add(trimmed);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("a stored set must contain at least one usable value");
        }
        return String.join(DELIMITER, cleaned);
    }

    /** Decodes a stored column. Tolerates null and extra whitespace, because rows outlive code. */
    public static Set<String> decode(String stored) {
        Set<String> values = new LinkedHashSet<>();
        if (stored == null || stored.isBlank()) {
            return values;
        }
        for (String part : stored.trim().split("\\s+")) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        return values;
    }
}
