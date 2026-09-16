package ru.ludwigandreas.archrules.report;

import java.util.List;
import java.util.Objects;

import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.RuleSeverity;

/**
 * The outcome of one rule: what it checks, what it found, how bad that is, and what to do about it.
 *
 * @param id            stable rule id - the same string that toggles the rule in configuration
 * @param group         the rule's group, for per-area aggregation
 * @param scope         {@code service}, or the module this instance was scoped to
 * @param description   ArchUnit's description of what the rule requires
 * @param severity      effective severity for this service
 * @param status        whether the rule was satisfied
 * @param remediation   what to change; empty when the rule set did not supply guidance
 * @param violations    every violation found, in the order ArchUnit reported them
 * @param durationMillis how long the rule took to evaluate, for spotting expensive rules
 */
public record RuleReport(RuleId id,
                         RuleGroup group,
                         String scope,
                         String description,
                         RuleSeverity severity,
                         RuleStatus status,
                         String remediation,
                         List<ViolationDetail> violations,
                         long durationMillis) {

    public RuleReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(remediation, "remediation");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    public boolean isViolated() {
        return status == RuleStatus.VIOLATED;
    }

    /** A violated rule that fails the build, as opposed to one that is only reported. */
    public boolean isFailure() {
        return isViolated() && severity == RuleSeverity.ERROR;
    }

    /** A violated rule the service has chosen to tolerate for now. */
    public boolean isWarning() {
        return isViolated() && severity == RuleSeverity.WARNING;
    }

    /** The name this rule appears under in the build report. */
    public String displayName() {
        return ru.ludwigandreas.archrules.ResolvedRule.SERVICE_SCOPE.equals(scope)
                ? id.value()
                : id.value() + " @ " + scope;
    }

    /**
     * The message used when this rule fails a test: the rule, every violation, and the fix. Written
     * for whoever - or whatever - reads the build log, which is why the remediation is repeated here
     * and not only in the JSON.
     */
    public String failureMessage() {
        StringBuilder message = new StringBuilder()
                .append('[').append(displayName()).append("] ")
                .append(description)
                .append(" - ").append(violations.size())
                .append(violations.size() == 1 ? " violation:" : " violations:");
        for (ViolationDetail violation : violations) {
            message.append(System.lineSeparator()).append("  - ").append(violation.message());
        }
        if (!remediation.isEmpty()) {
            message.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("How to fix: ").append(remediation);
        }
        return message.toString();
    }
}
