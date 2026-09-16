package ru.ludwigandreas.archrules;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import ru.ludwigandreas.archrules.internal.Selectors;

/**
 * Per-rule severity overrides, addressed by the same selectors as {@link RuleSelection}: {@code *},
 * a group id, a rule id, or a qualified rule id.
 *
 * <p>Downgrading a rule to a warning is the middle setting between enforcing it and disabling it:
 * the violations stay visible in both reports and in the org-wide JSON, but the build goes green
 * while the team works the debt down.
 *
 * <pre>{@code
 * SeverityPolicy.warn("web.controllers-do-not-call-controllers", "modules")
 * }</pre>
 */
public final class SeverityPolicy {

    private static final SeverityPolicy EMPTY = new SeverityPolicy(new LinkedHashMap<>());

    private final Map<String, RuleSeverity> overrides;

    private SeverityPolicy(Map<String, RuleSeverity> overrides) {
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }

    /** No overrides: every rule keeps the severity its rule set gave it. */
    public static SeverityPolicy none() {
        return EMPTY;
    }

    public static SeverityPolicy warn(String... selectors) {
        return EMPTY.with(RuleSeverity.WARNING, selectors);
    }

    public static SeverityPolicy fail(String... selectors) {
        return EMPTY.with(RuleSeverity.ERROR, selectors);
    }

    public SeverityPolicy with(RuleSeverity severity, String... selectors) {
        return with(severity, Arrays.asList(selectors));
    }

    public SeverityPolicy with(RuleSeverity severity, Collection<String> selectors) {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(selectors, "selectors");
        Map<String, RuleSeverity> merged = new LinkedHashMap<>(overrides);
        for (String selector : selectors) {
            merged.put(RuleSelection.validateSelector(selector), severity);
        }
        return new SeverityPolicy(merged);
    }

    public SeverityPolicy mergedWith(SeverityPolicy other) {
        Objects.requireNonNull(other, "other");
        if (other.overrides.isEmpty()) {
            return this;
        }
        Map<String, RuleSeverity> merged = new LinkedHashMap<>(overrides);
        merged.putAll(other.overrides);
        return new SeverityPolicy(merged);
    }

    /** The severity of the given rule: the most specific override, or the rule's own default. */
    public RuleSeverity severityOf(ArchitectureRule rule) {
        Objects.requireNonNull(rule, "rule");
        return Selectors.bestMatch(rule.id(), overrides, 0).orElseGet(rule::severity);
    }

    public Map<String, RuleSeverity> overrides() {
        return overrides;
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SeverityPolicy that && overrides.equals(that.overrides);
    }

    @Override
    public int hashCode() {
        return overrides.hashCode();
    }

    @Override
    public String toString() {
        return "SeverityPolicy" + overrides;
    }
}
