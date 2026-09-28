package ru.ludwigandreas.archrules.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.ArchitectureRulesConfiguration;
import ru.ludwigandreas.archrules.rules.BuiltInRuleSets;

/** Assembles the rule sets a configuration asks for: built-in, published via SPI, and explicit. */
public final class RuleSetLoader {

    private RuleSetLoader() {
    }

    public static List<ArchitectureRuleSet> load(ArchitectureRulesConfiguration configuration) {
        List<ArchitectureRuleSet> ruleSets = new ArrayList<>();
        if (configuration.includeBuiltInRuleSets()) {
            ruleSets.addAll(BuiltInRuleSets.all());
        }
        if (configuration.includeServiceLoaderRuleSets()) {
            ruleSets.addAll(fromServiceLoader());
        }
        ruleSets.addAll(configuration.additionalRuleSets());
        return List.copyOf(ruleSets);
    }

    /**
     * Rule sets a service publishes through
     * {@code META-INF/services/ru.ludwigandreas.archrules.ArchitectureRuleSet}. This is how a
     * platform team ships an organisation-wide convention as a jar that every service picks up by
     * adding the dependency, without editing any test code.
     */
    private static List<ArchitectureRuleSet> fromServiceLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : ArchitectureRuleSet.class.getClassLoader();
        List<ArchitectureRuleSet> discovered = new ArrayList<>();
        ServiceLoader.load(ArchitectureRuleSet.class, classLoader).forEach(discovered::add);
        return discovered;
    }
}
