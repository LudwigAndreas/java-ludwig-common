package ru.ludwigandreas.archrules;

import java.util.Objects;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;

/**
 * A rule that survived the toggles, bound to the set of classes it applies to and to the metadata the
 * reports need.
 *
 * <p>The scope is what makes per-module customization work: the service-wide instance of a rule is
 * evaluated against every class <em>except</em> those of the modules that took the rule over, and
 * each of those modules gets its own instance evaluated against its own classes only.
 *
 * @param id        identity of the underlying rule
 * @param scopeName name of the scope this instance belongs to, e.g. {@code service} or a module
 * @param rule      the ArchUnit rule, already wrapped with the suite-wide settings
 * @param scope       the classes this instance is evaluated against
 * @param severity    effective severity after the service's {@link SeverityPolicy} was applied
 * @param remediation what to change when this rule is violated
 */
public record ResolvedRule(RuleId id,
                           String scopeName,
                           ArchRule rule,
                           DescribedPredicate<JavaClass> scope,
                           RuleSeverity severity,
                           String remediation) {

    /** Name of the scope covering the whole service. */
    public static final String SERVICE_SCOPE = "service";

    public ResolvedRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scopeName, "scopeName");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(remediation, "remediation");
    }

    public RuleGroup group() {
        return id.group();
    }

    /** The name this rule appears under in the build report. */
    public String displayName() {
        return SERVICE_SCOPE.equals(scopeName) ? id.value() : id.value() + " @ " + scopeName;
    }

    public String description() {
        return rule.getDescription();
    }

    /** Runs the rule, throwing {@link AssertionError} on violations. */
    public void check(JavaClasses classes) {
        rule.check(classesInScope(classes));
    }

    /** Runs the rule and returns the result instead of throwing. */
    public EvaluationResult evaluate(JavaClasses classes) {
        return rule.evaluate(classesInScope(classes));
    }

    private JavaClasses classesInScope(JavaClasses classes) {
        return classes.that(scope);
    }

    @Override
    public String toString() {
        return displayName();
    }
}
