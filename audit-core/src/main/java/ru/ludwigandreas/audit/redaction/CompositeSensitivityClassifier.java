package ru.ludwigandreas.audit.redaction;

import java.util.ArrayList;
import java.util.List;

/**
 * The union of several classifiers, as a named type.
 *
 * <p>{@link SensitivityClassifier#anyOf} already composes, and returns a lambda. This class exists because
 * the composition has to be recognisable: the autoconfiguration composes <em>every</em>
 * {@code SensitivityClassifier} bean in the context - the deployment-wide configured rules plus whatever
 * modules contribute, such as user-settings' PII declarations - and the composed bean is itself a
 * {@code SensitivityClassifier}, so without a type to exclude it would collect itself.
 *
 * <p>Union, and only ever union, for the reason on {@link SensitivityClassifier#anyOf}: a rule that let one
 * classifier clear what another flagged is a rule that leaks. A module can therefore only ever widen what
 * the platform masks, never narrow it.
 */
public final class CompositeSensitivityClassifier implements SensitivityClassifier {

    private final List<SensitivityClassifier> delegates;

    /**
     * Composes the given classifiers.
     *
     * @param delegates the classifiers; nulls are dropped rather than rejected, so an autoconfiguration can
     *                  pass a conditionally-created bean without a guard at every call
     */
    public CompositeSensitivityClassifier(List<SensitivityClassifier> delegates) {
        List<SensitivityClassifier> present = new ArrayList<>();
        if (delegates != null) {
            delegates.stream().filter(one -> one != null).forEach(present::add);
        }
        this.delegates = List.copyOf(present);
    }

    /** The classifiers this composite asks, in the order it asks them. */
    public List<SensitivityClassifier> delegates() {
        return delegates;
    }

    @Override
    public boolean isSensitive(String provenance, String key) {
        return delegates.stream().anyMatch(one -> one.isSensitive(provenance, key));
    }
}
