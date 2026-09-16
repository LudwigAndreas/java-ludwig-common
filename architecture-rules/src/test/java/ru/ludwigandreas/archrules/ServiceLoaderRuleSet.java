package ru.ludwigandreas.archrules;

import java.util.List;

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Published through {@code META-INF/services} so that {@link ServiceLoaderDiscoveryTest} can prove
 * the discovery path a platform team relies on: a jar on the test classpath contributes its rules to
 * every service that depends on it, with no test code to change.
 */
public final class ServiceLoaderRuleSet implements ArchitectureRuleSet {

    public static final RuleId RULE_ID = RuleId.of(RuleGroup.CUSTOM, "contributed-through-service-loader");

    @Override
    public RuleGroup group() {
        return RuleGroup.CUSTOM;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        // Deliberately something nothing can violate: this rule set exists to prove the discovery
        // path, not to check anything about the fixtures.
        return List.of(ArchitectureRule.of(RULE_ID, ArchRuleDefinition.noClasses()
                        .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
                        .as("Contributed rule for " + context.scopeName()),
                "Nothing to fix - this rule is a test of the ServiceLoader discovery path."));
    }
}
