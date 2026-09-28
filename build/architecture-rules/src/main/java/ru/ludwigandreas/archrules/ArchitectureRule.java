package ru.ludwigandreas.archrules;

import java.util.Objects;

import com.tngtech.archunit.lang.ArchRule;

/**
 * One ArchUnit rule plus the metadata this library needs around it: its {@link RuleId}, whether it is
 * on unless asked for, how badly it fails, and what a developer - or an agent reading the JSON
 * report - should do about a violation.
 *
 * @param id               stable identity, used for toggling and for the test name
 * @param rule             the ArchUnit rule itself
 * @param enabledByDefault {@code false} marks a rule that its own group does not turn on - a
 *                         stricter variant a service has to ask for explicitly by id
 * @param severity         default severity, overridable per service through {@link SeverityPolicy}
 * @param remediation      one or two sentences saying what to change, written to be actionable
 *                         without the surrounding conversation; it is the {@code remediation} field
 *                         of every violation in the JSON report
 */
public record ArchitectureRule(RuleId id,
                               ArchRule rule,
                               boolean enabledByDefault,
                               RuleSeverity severity,
                               String remediation) {

    public ArchitectureRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(remediation, "remediation");
    }

    /** A rule that is active whenever its group is. */
    public static ArchitectureRule of(RuleId id, ArchRule rule) {
        return new ArchitectureRule(id, rule, true, RuleSeverity.ERROR, "");
    }

    /** A rule that is active whenever its group is, carrying the fix for its violations. */
    public static ArchitectureRule of(RuleId id, ArchRule rule, String remediation) {
        return new ArchitectureRule(id, rule, true, RuleSeverity.ERROR, remediation);
    }

    /** A stricter rule that a service must enable explicitly by id, even within an enabled group. */
    public static ArchitectureRule optIn(RuleId id, ArchRule rule) {
        return new ArchitectureRule(id, rule, false, RuleSeverity.ERROR, "");
    }

    /** A stricter rule that a service must enable explicitly by id, carrying its fix. */
    public static ArchitectureRule optIn(RuleId id, ArchRule rule, String remediation) {
        return new ArchitectureRule(id, rule, false, RuleSeverity.ERROR, remediation);
    }

    public RuleGroup group() {
        return id.group();
    }

    public String description() {
        return rule.getDescription();
    }

    /** Returns a copy carrying a transformed ArchUnit rule (used to apply suite-wide settings). */
    public ArchitectureRule withRule(ArchRule transformed) {
        return new ArchitectureRule(id, transformed, enabledByDefault, severity, remediation);
    }

    /** Returns a copy with another default severity. */
    public ArchitectureRule withSeverity(RuleSeverity newSeverity) {
        return new ArchitectureRule(id, rule, enabledByDefault, newSeverity, remediation);
    }

    /** Returns a copy carrying remediation guidance. */
    public ArchitectureRule withRemediation(String newRemediation) {
        return new ArchitectureRule(id, rule, enabledByDefault, severity, newRemediation);
    }

    @Override
    public String toString() {
        return id.value();
    }
}
