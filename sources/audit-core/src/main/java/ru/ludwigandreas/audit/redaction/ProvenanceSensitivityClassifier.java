package ru.ludwigandreas.audit.redaction;

import java.util.List;
import java.util.Locale;

/**
 * Sensitive because of where the value came from, whatever it is called.
 *
 * <p>{@code hot-reload-spring-boot-starter}'s second rule, and the one a name heuristic cannot
 * replace: every key sourced from Vault is treated as sensitive regardless of its name, since that is
 * presumptively why it is in Vault. The shipped prefixes are the source-id prefixes that module uses.
 *
 * <p>Matched as a prefix rather than an equality, because a source id carries its own coordinates after
 * the scheme - {@code vault:secret/data/app} is one source and {@code vault:secret/data/db} another.
 */
public final class ProvenanceSensitivityClassifier implements SensitivityClassifier {

    /** The source-id prefixes the platform treats as wholly sensitive. */
    public static final List<String> DEFAULT_PREFIXES = List.of("vault:", "vault-lease:");

    private final List<String> prefixes;

    /** A classifier over {@link #DEFAULT_PREFIXES}. */
    public ProvenanceSensitivityClassifier() {
        this(DEFAULT_PREFIXES);
    }

    /**
     * A classifier over a deployment's own prefixes.
     *
     * @param prefixes provenance prefixes, matched case-insensitively; null or empty classifies
     *                 nothing, which is what a deployment with no secret store wants
     */
    public ProvenanceSensitivityClassifier(List<String> prefixes) {
        this.prefixes = prefixes == null ? List.of()
                : prefixes.stream().filter(one -> one != null && !one.isBlank())
                        .map(one -> one.toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    public boolean isSensitive(String provenance, String key) {
        if (provenance == null) {
            return false;
        }
        String lowered = provenance.toLowerCase(Locale.ROOT);
        return prefixes.stream().anyMatch(lowered::startsWith);
    }
}
