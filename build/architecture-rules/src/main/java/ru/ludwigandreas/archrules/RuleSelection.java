package ru.ludwigandreas.archrules;

import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import ru.ludwigandreas.archrules.internal.Selectors;

/**
 * Which rules are on. A selection is a list of selectors mapped to on/off, where a selector is
 * {@code *}, a group id ({@code kafka}), a rule id ({@code kafka.listeners-do-not-use-repositories})
 * or a qualified rule id ({@code cycles.module-internals[com.acme.orders]}).
 *
 * <p>The most specific selector that matches a rule decides, so
 * {@code disable("web").enable("web.controllers-do-not-call-controllers")} keeps exactly that one
 * rule of an otherwise disabled group. Order within a specificity level matters too - a later
 * setting replaces an earlier one, which is what lets a per-module selection override the global
 * one.
 *
 * <p>Instances are immutable; every mutator returns a new selection.
 */
public final class RuleSelection {

    private static final RuleSelection EMPTY = new RuleSelection(new LinkedHashMap<>());

    private final Map<String, Boolean> overrides;

    private RuleSelection(Map<String, Boolean> overrides) {
        // LinkedHashMap, not Map.copyOf: two selectors of equal specificity are resolved by
        // declaration order, so the iteration order is part of the semantics.
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }

    /** No opinion: every rule falls back to its own and its group's default. */
    public static RuleSelection none() {
        return EMPTY;
    }

    public static RuleSelection enabling(String... selectors) {
        return EMPTY.enable(selectors);
    }

    public static RuleSelection disabling(String... selectors) {
        return EMPTY.disable(selectors);
    }

    public RuleSelection enable(String... selectors) {
        return with(Arrays.asList(selectors), true);
    }

    public RuleSelection enable(Collection<String> selectors) {
        return with(selectors, true);
    }

    public RuleSelection disable(String... selectors) {
        return with(Arrays.asList(selectors), false);
    }

    public RuleSelection disable(Collection<String> selectors) {
        return with(selectors, false);
    }

    /** This selection, with {@code other}'s settings applied on top of it. */
    public RuleSelection mergedWith(RuleSelection other) {
        Objects.requireNonNull(other, "other");
        if (other.overrides.isEmpty()) {
            return this;
        }
        Map<String, Boolean> merged = new LinkedHashMap<>(overrides);
        merged.putAll(other.overrides);
        return new RuleSelection(merged);
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    public Map<String, Boolean> overrides() {
        return overrides;
    }

    /**
     * Whether the given rule runs. Rule-level settings win outright; a group-level setting can only
     * switch a group off, or switch on the rules the group would have run anyway - so enabling a
     * whole group never silently activates the stricter opt-in rules inside it.
     */
    public boolean isEnabled(ArchitectureRule rule) {
        Objects.requireNonNull(rule, "rule");
        Optional<Boolean> ruleLevel = bestMatch(rule.id(), 2);
        if (ruleLevel.isPresent()) {
            return ruleLevel.get();
        }
        boolean groupEnabled = bestMatch(rule.id(), 0).orElseGet(() -> rule.group().enabledByDefault());
        return groupEnabled && rule.enabledByDefault();
    }

    /** Whether any selector addresses the given rule directly (as opposed to via its group). */
    public boolean hasRuleLevelOverrideFor(RuleId id) {
        return bestMatch(id, 2).isPresent();
    }

    /** Whether any selector addresses the given rule at all, group and wildcard selectors included. */
    public boolean hasAnyOverrideFor(RuleId id) {
        return bestMatch(id, 0).isPresent();
    }

    private Optional<Boolean> bestMatch(RuleId id, int minimumSpecificity) {
        return Selectors.bestMatch(id, overrides, minimumSpecificity);
    }

    private RuleSelection with(Collection<String> selectors, boolean enabled) {
        Objects.requireNonNull(selectors, "selectors");
        Map<String, Boolean> merged = new LinkedHashMap<>(overrides);
        for (String selector : selectors) {
            merged.put(validateSelector(selector), enabled);
        }
        return new RuleSelection(merged);
    }

    static String validateSelector(String selector) {
        Objects.requireNonNull(selector, "selector");
        String normalized = selector.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Rule selector must not be blank");
        }
        if ("*".equals(normalized)) {
            return normalized;
        }
        String groupId = normalized.contains(".") ? normalized.substring(0, normalized.indexOf('.')) : normalized;
        if (RuleGroup.byId(groupId).isEmpty()) {
            throw new IllegalArgumentException("Unknown rule selector '" + selector
                    + "': it must be '*', a rule group " + RuleGroup.ids()
                    + ", or a rule id such as 'persistence.entities-reside-in-entity-packages'");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuleSelection that && overrides.equals(that.overrides);
    }

    @Override
    public int hashCode() {
        return overrides.hashCode();
    }

    @Override
    public String toString() {
        return "RuleSelection" + overrides;
    }
}
