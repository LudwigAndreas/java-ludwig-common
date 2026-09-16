package ru.ludwigandreas.archrules.rules;

import java.util.List;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.ExternalLibrary;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * Production code does not depend on a test framework.
 *
 * <p>It is an easy mistake to make - a JUnit {@code Assertions.assertTrue} used as a guard clause, a
 * Mockito-based fake left in {@code src/main} for "convenience", an AssertJ import in a utility -
 * and an expensive one: the test dependency is test-scoped, so the code compiles and the build stays
 * green while the application fails at runtime with {@code NoClassDefFoundError}, in production,
 * only on the path that touches it.
 *
 * <p>The check depends on the import including production classes only, which is the default here
 * (see {@code ArchitectureRulesConfiguration}); a service that widens the import to test sources
 * should switch this group off along with it.
 */
public final class TestSeparationRules implements ArchitectureRuleSet {

    /** No test framework is reachable from production code. */
    public static final RuleId NO_TEST_FRAMEWORKS_IN_PRODUCTION =
            RuleId.of(RuleGroup.TEST_SEPARATION, "production-code-does-not-depend-on-test-frameworks");

    @Override
    public RuleGroup group() {
        return RuleGroup.TEST_SEPARATION;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(ArchitectureRule.of(NO_TEST_FRAMEWORKS_IN_PRODUCTION, ArchRuleDefinition.classes()
                .should(ArchitectureConditions.notDependOnClassesThat(
                        ArchitecturePredicates.residingIn(context.libraryPackages(ExternalLibrary.TEST_FRAMEWORK))
                                .as("test frameworks " + context.libraryPackages(ExternalLibrary.TEST_FRAMEWORK)),
                        "a test framework - test-scoped dependencies are absent at runtime"))
                .as("Production code does not depend on test frameworks"),
                "Replace the test-framework call with production code: a plain if/throw instead of an"
                        + " assertion, a hand-written stub instead of a Mockito mock. Test-scoped dependencies"
                        + " are absent at runtime, so this compiles and then fails in production with"
                        + " NoClassDefFoundError."));
    }
}
