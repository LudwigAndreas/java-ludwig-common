package ru.ludwigandreas.audit.redaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a named value is sensitive. Does not decide what to do about it.
 *
 * <p>Splitting classification from masking is what makes one redaction package possible. The three
 * implementations this replaces looked like three redactors and were actually three
 * <em>classifiers</em> feeding one masking action:
 *
 * <table>
 *   <caption>The three classification rules, and what they had in common</caption>
 *   <tr><th>module</th><th>how it classified</th><th>what it did</th></tr>
 *   <tr><td>hot-reload</td><td>key-name regex, or provenance - anything from Vault, whatever its
 *       name</td><td>scalar to a fixed marker</td></tr>
 *   <tr><td>rest-client</td><td>explicit configured header and field lists, matched
 *       case-insensitively</td><td>header map, JSON tree, form body, truncation</td></tr>
 *   <tr><td>user-settings</td><td>declarative - {@code SettingDefinition.isPii()}</td><td>scalar to a
 *       fixed marker</td></tr>
 * </table>
 *
 * <p>Genuinely different rules, and all three are still needed: a name heuristic finds the secret
 * nobody configured, a configured list is the only thing that catches a credential in a field called
 * {@code x-partner-key}, and a declaration is the only one that knows a field called
 * {@code mobile} is personal data. So they compose - see {@link #anyOf} - rather than one of them
 * winning.
 *
 * <h2>{@code provenance}</h2>
 *
 * <p>Where the value came from, which for the hot-reload module is the source id ({@code vault:},
 * {@code file:}) and for the REST client is the named client. It is a separate argument from the key
 * because the hot-reload rule turns on it alone: every Vault-sourced key is sensitive regardless of
 * its name, since that is presumptively why it is in Vault.
 */
@FunctionalInterface
public interface SensitivityClassifier {

    /**
     * Whether a value under {@code key} from {@code provenance} is sensitive.
     *
     * @param provenance where the value came from, or {@code null} when the caller has no such notion
     * @param key        the key, field, header or setting name; never the value, because a classifier
     *                   that inspected values would itself become a place values are read and logged
     * @return whether it must be masked before it is written anywhere
     */
    boolean isSensitive(String provenance, String key);

    /** Classifies nothing as sensitive. */
    static SensitivityClassifier none() {
        return (provenance, key) -> false;
    }

    /**
     * Sensitive if <em>any</em> delegate says so.
     *
     * <p>Union and not intersection, and not a priority order either. A value sensitive by provenance
     * but innocuous by name - a Vault key called {@code timeout} - is sensitive, and a value
     * innocuous by provenance but named {@code client_secret} is sensitive too. Any composition rule
     * that lets one classifier clear what another flagged is a rule that leaks, and the cost of the
     * union being wrong is a masked timeout in a log line.
     *
     * @param delegates the classifiers to combine; nulls are dropped
     * @return the union
     */
    static SensitivityClassifier anyOf(SensitivityClassifier... delegates) {
        return anyOf(delegates == null ? List.of() : List.of(delegates));
    }

    /**
     * Sensitive if any delegate says so.
     *
     * @param delegates the classifiers to combine; nulls are dropped
     * @return the union
     */
    static SensitivityClassifier anyOf(List<SensitivityClassifier> delegates) {
        List<SensitivityClassifier> present = new ArrayList<>();
        if (delegates != null) {
            delegates.stream().filter(one -> one != null).forEach(present::add);
        }
        List<SensitivityClassifier> effective = List.copyOf(present);
        return (provenance, key) -> effective.stream()
                .anyMatch(one -> one.isSensitive(provenance, key));
    }
}
