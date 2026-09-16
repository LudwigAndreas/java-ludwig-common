package ru.ludwigandreas.archrules.rules;

import java.util.List;

import ru.ludwigandreas.archrules.ArchitectureRuleSet;

/**
 * The rule sets this library ships, in the order they appear in a build report.
 *
 * <p>Registering a new built-in rule set means adding it here; everything else - toggling, per-module
 * customization, property configuration - follows from its {@link ArchitectureRuleSet#group()} and
 * the ids of its rules.
 */
public final class BuiltInRuleSets {

    private static final List<ArchitectureRuleSet> ALL = List.of(
            new LayeringRules(),
            new CycleRules(),
            new DomainIsolationRules(),
            new WebRules(),
            new PersistenceRules(),
            new KafkaRules(),
            new StorageRules(),
            new SpringWiringRules(),
            new ModuleBoundaryRules(),
            new TestSeparationRules(),
            new ConfigurationAccessRules(),
            new ExceptionRules(),
            new DtoImmutabilityRules(),
            new EntityBaseRules(),
            new KafkaContractRules(),
            new RestPathRules(),
            new ConfigurationPropertiesRules(),
            new OptionalUsageRules(),
            new MapperConventionRules());

    private BuiltInRuleSets() {
    }

    public static List<ArchitectureRuleSet> all() {
        return ALL;
    }
}
