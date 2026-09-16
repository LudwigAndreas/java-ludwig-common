package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.ludwigandreas.archrules.rules.PersistenceRules;
import ru.ludwigandreas.archrules.rules.SpringWiringRules;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-module customization: the same suite, with one module playing by slightly different rules.
 *
 * <p>The behaviour worth pinning down is the hand-over. When a module takes a rule over, the
 * service-wide instance must stop applying to that module's classes - otherwise a module could never
 * actually deviate - while continuing to apply to everything else.
 */
class ModuleCustomizationTest {

    private static final String SPRING_FIXTURE = Fixtures.ROOT + ".bad.spring";
    private static final String PERSISTENCE_FIXTURE = Fixtures.ROOT + ".bad.datastore";

    @Test
    @DisplayName("a module can switch off a rule that keeps applying to the rest of the service")
    void aModuleCanDisableARule() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad").build()))
                .contains(SpringWiringRules.CONSTRUCTOR_INJECTION.value());

        ArchitectureRulesConfiguration configuration = Fixtures.configurationFor("bad")
                .customizeModules(ModuleRuleCustomization.forModules(SPRING_FIXTURE)
                        .disable(SpringWiringRules.CONSTRUCTOR_INJECTION.value())
                        .build())
                .build();

        assertThat(Fixtures.failingRuleIds(configuration))
                .doesNotContain(SpringWiringRules.CONSTRUCTOR_INJECTION.value());

        List<ResolvedRule> instances = ArchitectureRules.suite(configuration).rules().stream()
                .filter(rule -> rule.id().equals(SpringWiringRules.CONSTRUCTOR_INJECTION))
                .toList();
        assertThat(instances).singleElement()
                .satisfies(rule -> assertThat(rule.scopeName()).isEqualTo(ResolvedRule.SERVICE_SCOPE));
    }

    @Test
    @DisplayName("a module can redefine the conventions the rules are built from")
    void aModuleCanRedefineConventions() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.datastore").build()))
                .contains(PersistenceRules.ENTITIES_IN_ENTITY_PACKAGES.value());

        ArchitectureRulesConfiguration configuration = Fixtures.configurationFor("bad.datastore")
                .customizeModules(ModuleRuleCustomization.forModules(PERSISTENCE_FIXTURE + ".catalog")
                        // this module keeps its entities next to the service that owns them
                        .conventions(conventions -> conventions.addPackages(PackageRole.ENTITY, "..service.."))
                        .build())
                .build();

        assertThat(Fixtures.failingRuleIds(configuration))
                .doesNotContain(PersistenceRules.ENTITIES_IN_ENTITY_PACKAGES.value());
    }

    @Test
    @DisplayName("a module can be given extra rules of its own")
    void aModuleCanAddRules() {
        RuleId onlyHere = RuleId.of(RuleGroup.CUSTOM, "module-specific-rule");
        ArchitectureRulesConfiguration configuration = Fixtures.configurationFor("bad")
                .customizeModules(ModuleRuleCustomization.forModules(SPRING_FIXTURE)
                        .addRuleSet(new SingleRuleSet(onlyHere))
                        .build())
                .build();

        List<ResolvedRule> instances = ArchitectureRules.suite(configuration).rules().stream()
                .filter(rule -> rule.id().equals(onlyHere))
                .toList();

        assertThat(instances).singleElement()
                .satisfies(rule -> assertThat(rule.scopeName()).isEqualTo(SPRING_FIXTURE));
    }

    @Test
    @DisplayName("a service-wide custom rule set is checked alongside the built-in ones")
    void aServiceCanAddItsOwnRuleSet() {
        RuleId custom = RuleId.of(RuleGroup.CUSTOM, "service-wide-rule");

        Set<String> resolved = Fixtures.resolvedRuleIds(Fixtures.configurationFor("good")
                .addRuleSet(new SingleRuleSet(custom))
                .build());

        assertThat(resolved).contains(custom.value());
    }

    @Test
    @DisplayName("built-in rule sets can be switched off entirely, leaving only the service's own")
    void builtInRuleSetsCanBeReplaced() {
        RuleId custom = RuleId.of(RuleGroup.CUSTOM, "the-only-rule");

        Set<String> resolved = Fixtures.resolvedRuleIds(Fixtures.configurationFor("good")
                .includeBuiltInRuleSets(false)
                // ServiceLoaderRuleSet is published on this module's own test classpath
                .includeServiceLoaderRuleSets(false)
                .addRuleSet(new SingleRuleSet(custom))
                .build());

        assertThat(resolved).containsExactly(custom.value());
    }

    /** A rule set contributing one rule that nothing can violate; these tests check wiring. */
    private record SingleRuleSet(RuleId id) implements ArchitectureRuleSet {

        @Override
        public RuleGroup group() {
            return id.group();
        }

        @Override
        public List<ArchitectureRule> rules(RuleContext context) {
            return List.of(ArchitectureRule.of(id, com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                    .should().bePublic()
                    .as("Custom rule " + id.value())));
        }
    }
}
