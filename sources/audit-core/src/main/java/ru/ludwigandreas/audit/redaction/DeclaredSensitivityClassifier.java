package ru.ludwigandreas.audit.redaction;

import java.util.function.Predicate;

/**
 * Sensitive because the thing that defines it says so.
 *
 * <p>{@code user-settings-spring-boot-starter}'s rule, generalised to a predicate over the key:
 * {@code SettingDefinition.isPii()}. The most reliable of the four classifiers and the least
 * transferable, because it needs a registry of definitions to ask - which is exactly why it is a
 * predicate here rather than a dependency on one.
 *
 * <p>It is also the only classifier that recognises <em>personal</em> data as opposed to
 * <em>secret</em> data. A setting called {@code mobile} matches no secret-name heuristic and appears in
 * no partner's header list, and a phone number in a trail retained for years outlives every erasure
 * request that was meant to remove it.
 */
public final class DeclaredSensitivityClassifier implements SensitivityClassifier {

    private final Predicate<String> declaredSensitive;

    /**
     * A classifier that asks {@code declaredSensitive} about each key.
     *
     * @param declaredSensitive answers whether the definition of this key is flagged; null classifies
     *                          nothing, so a deployment with no registry is simply left to the other
     *                          classifiers rather than failing
     */
    public DeclaredSensitivityClassifier(Predicate<String> declaredSensitive) {
        this.declaredSensitive = declaredSensitive == null ? key -> false : declaredSensitive;
    }

    @Override
    public boolean isSensitive(String provenance, String key) {
        return key != null && declaredSensitive.test(key);
    }
}
