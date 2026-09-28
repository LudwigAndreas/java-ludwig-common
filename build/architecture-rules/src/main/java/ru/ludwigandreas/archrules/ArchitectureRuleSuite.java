package ru.ludwigandreas.archrules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.tngtech.archunit.core.domain.JavaClasses;

import ru.ludwigandreas.archrules.internal.ImportedClasses;
import ru.ludwigandreas.archrules.internal.SuiteFactory;

/**
 * The rules a configuration resolves to, bound to the classes they will be checked against.
 *
 * <p>Obtained from {@link ArchitectureRules#suite(ArchitectureRulesConfiguration)}. A JUnit
 * integration turns each {@link ResolvedRule} into its own test (see
 * {@link ru.ludwigandreas.archrules.junit.ArchitectureRulesTest}); {@link #check()} is the
 * runner-independent alternative that evaluates everything and reports all failures at once.
 */
public final class ArchitectureRuleSuite {

    private final ArchitectureRulesConfiguration configuration;
    private final JavaClasses classes;
    private final List<ResolvedRule> rules;

    private ArchitectureRuleSuite(ArchitectureRulesConfiguration configuration, JavaClasses classes) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.classes = Objects.requireNonNull(classes, "classes");
        // A mistyped base package imports nothing, and with allowEmptyShould every rule then passes
        // against an empty class set - a green architecture test for a service that was never
        // checked. Refusing the empty analysis is the one place that can catch it.
        if (classes.isEmpty() && !configuration.allowEmptyAnalysis()) {
            throw new IllegalStateException("No classes were imported from " + configuration.basePackages()
                    + ", so every architecture rule would pass without checking anything. Verify the base"
                    + " packages and the import options (test sources are excluded by default), or call"
                    + " allowEmptyAnalysis(true) if an empty module is expected.");
        }
        this.rules = SuiteFactory.resolve(configuration, classes);
    }

    static ArchitectureRuleSuite of(ArchitectureRulesConfiguration configuration) {
        return new ArchitectureRuleSuite(configuration, ImportedClasses.of(configuration));
    }

    static ArchitectureRuleSuite of(ArchitectureRulesConfiguration configuration, JavaClasses classes) {
        return new ArchitectureRuleSuite(configuration, classes);
    }

    /** The enabled rules, in the order they were resolved. */
    public List<ResolvedRule> rules() {
        return rules;
    }

    /** The imported classes under analysis. */
    public JavaClasses classes() {
        return classes;
    }

    public ArchitectureRulesConfiguration configuration() {
        return configuration;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * Evaluates every rule and, if any failed, throws a single {@link AssertionError} listing all of
     * them. Failing on the first violation would hide the rest of the report and turn one cleanup
     * into a sequence of builds.
     *
     * <p>Rules the service downgraded to {@link RuleSeverity#WARNING} are evaluated but do not fail
     * here either - severity would be meaningless if it only applied to the JUnit runner. Use
     * {@link ArchitectureRules#checkAndReport} to see warnings in the reports as well.
     */
    public void check() {
        List<String> failures = new ArrayList<>();
        for (ResolvedRule rule : rules) {
            if (rule.severity() == RuleSeverity.WARNING) {
                continue;
            }
            try {
                rule.check(classes);
            } catch (AssertionError failure) {
                failures.add("[" + rule.displayName() + "] " + failure.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.format("%d of %d architecture rules failed:%n%n%s",
                    failures.size(), rules.size(), String.join(System.lineSeparator() + System.lineSeparator(),
                    failures)));
        }
    }

    @Override
    public String toString() {
        return "ArchitectureRuleSuite" + rules.stream().map(ResolvedRule::displayName).toList();
    }
}
