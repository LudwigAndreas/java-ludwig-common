package ru.ludwigandreas.audit.redaction;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sensitive because a deployment named it.
 *
 * <p>{@code rest-client-spring-boot-starter}'s rule: explicit header and field lists, per named client.
 * It is the only classifier that can catch a credential in a field called {@code x-partner-key}, which
 * no heuristic recognises and no declaration covers because the shape belongs to somebody else's API.
 *
 * <p><b>Matched case-insensitively, which is not pedantry.</b> The reasoning is
 * {@code HeaderRedactor}'s and is kept because it is a real bug it prevents: HTTP header names are
 * case-insensitive by specification, and a partner that sends {@code authorization} in lower case would
 * otherwise sail straight past a list containing {@code Authorization}. The same holds for JSON field
 * names in practice, where {@code accessToken} and {@code access_token} are the same field to everyone
 * except a case-sensitive set.
 */
public final class ConfiguredNamesSensitivityClassifier implements SensitivityClassifier {

    private final Set<String> names;

    /**
     * A classifier over an explicit list of names.
     *
     * @param names header, field or key names; null or empty classifies nothing
     */
    public ConfiguredNamesSensitivityClassifier(Collection<String> names) {
        Set<String> lowered = new TreeSet<>();
        if (names != null) {
            names.stream().filter(one -> one != null && !one.isBlank())
                    .forEach(one -> lowered.add(one.toLowerCase(Locale.ROOT)));
        }
        this.names = Set.copyOf(lowered);
    }

    /** The names this classifier matches, lower-cased. */
    public Set<String> names() {
        return names;
    }

    @Override
    public boolean isSensitive(String provenance, String key) {
        return key != null && names.contains(key.toLowerCase(Locale.ROOT));
    }
}
