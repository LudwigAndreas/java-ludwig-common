package ru.ludwigandreas.archrules.internal;

import java.util.Map;
import java.util.Optional;

import ru.ludwigandreas.archrules.RuleId;

/**
 * Resolution of selector-keyed settings against a rule id, shared by everything that is configured
 * per rule: which rules run, and at which severity.
 *
 * <p>The most specific selector wins - a qualified rule id beats a rule id, which beats a group id,
 * which beats {@code *} - and ties are broken by declaration order, which is why the maps that feed
 * this must preserve insertion order.
 */
public final class Selectors {

    private Selectors() {
    }

    /**
     * The value of the most specific selector matching {@code id}, ignoring selectors less specific
     * than {@code minimumSpecificity} (0 includes {@code *}, 2 considers rule-level selectors only).
     */
    public static <T> Optional<T> bestMatch(RuleId id, Map<String, T> bySelector, int minimumSpecificity) {
        int bestSpecificity = -1;
        T best = null;
        for (Map.Entry<String, T> entry : bySelector.entrySet()) {
            int specificity = id.specificityOf(entry.getKey());
            if (specificity >= minimumSpecificity && specificity >= bestSpecificity) {
                bestSpecificity = specificity;
                best = entry.getValue();
            }
        }
        return Optional.ofNullable(best);
    }
}
