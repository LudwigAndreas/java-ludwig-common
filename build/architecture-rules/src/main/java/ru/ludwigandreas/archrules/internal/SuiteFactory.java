package ru.ludwigandreas.archrules.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import ru.ludwigandreas.archrules.ArchitectureConventions;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.ArchitectureRulesConfiguration;
import ru.ludwigandreas.archrules.ModuleRuleCustomization;
import ru.ludwigandreas.archrules.ResolvedRule;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleSelection;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * Turns a configuration plus an imported class set into the flat, ordered list of rules to run.
 *
 * <p>The interesting part is how a {@link ModuleRuleCustomization} is folded in. A customization
 * <em>takes over</em> a rule when it redefines the conventions the rule is built from, or when its
 * selection names the rule. A taken-over rule is then built twice: once service-wide, scoped to
 * exclude the customizing modules, and once per customization, scoped to that module and built from
 * that module's conventions and toggles. Every other rule stays a single service-wide instance. The
 * result is that each class is checked by exactly one instance of each rule, which is what keeps the
 * failure output unambiguous.
 */
public final class SuiteFactory {

    private SuiteFactory() {
    }

    public static List<ResolvedRule> resolve(ArchitectureRulesConfiguration configuration, JavaClasses classes) {
        List<ArchitectureRuleSet> ruleSets = RuleSetLoader.load(configuration);
        List<String> modules = configuration.modulePackages().isEmpty()
                ? ModuleDiscovery.discover(classes, configuration.basePackages())
                : configuration.modulePackages();

        List<ResolvedRule> resolved = new ArrayList<>();
        resolved.addAll(serviceWideRules(configuration, ruleSets, modules));
        for (ModuleRuleCustomization customization : configuration.moduleCustomizations()) {
            resolved.addAll(moduleRules(configuration, ruleSets, customization));
        }
        rejectDuplicates(resolved);
        return List.copyOf(resolved);
    }

    private static ResolvedRule resolve(ArchitectureRulesConfiguration configuration,
                                        ArchitectureRule rule,
                                        String scopeName,
                                        DescribedPredicate<JavaClass> scope) {
        return new ResolvedRule(rule.id(), scopeName, applySuiteSettings(configuration, rule.rule()), scope,
                configuration.severities().severityOf(rule), rule.remediation());
    }

    private static List<ResolvedRule> serviceWideRules(ArchitectureRulesConfiguration configuration,
                                                       List<ArchitectureRuleSet> ruleSets,
                                                       List<String> modules) {
        RuleContext context = new RuleContext(ResolvedRule.SERVICE_SCOPE,
                configuration.basePackages(), modules, configuration.conventions());
        List<ResolvedRule> resolved = new ArrayList<>();
        for (ArchitectureRuleSet ruleSet : ruleSets) {
            for (ArchitectureRule rule : ruleSet.rules(context)) {
                if (!configuration.selection().isEnabled(rule)) {
                    continue;
                }
                List<String> excluded = configuration.moduleCustomizations().stream()
                        .filter(customization -> customization.takesOver(rule.id()))
                        .flatMap(customization -> customization.modulePackageIdentifiers().stream())
                        .toList();
                resolved.add(resolve(configuration, rule, ResolvedRule.SERVICE_SCOPE, serviceScope(excluded)));
            }
        }
        return resolved;
    }

    private static List<ResolvedRule> moduleRules(ArchitectureRulesConfiguration configuration,
                                                  List<ArchitectureRuleSet> sharedRuleSets,
                                                  ModuleRuleCustomization customization) {
        ArchitectureConventions conventions = customization.conventionsFrom(configuration.conventions());
        RuleSelection selection = configuration.selection().mergedWith(customization.selection());
        String scopeName = String.join(", ", customization.modulePackages());
        RuleContext context = new RuleContext(scopeName,
                customization.modulePackages(), customization.modulePackages(), conventions);
        DescribedPredicate<JavaClass> scope =
                ArchitecturePredicates.insideAnyOf(customization.modulePackages());

        List<ResolvedRule> resolved = new ArrayList<>();
        for (ArchitectureRuleSet ruleSet : sharedRuleSets) {
            for (ArchitectureRule rule : ruleSet.rules(context)) {
                // Rules the customization does not take over keep running service-wide, module
                // classes included - building them here as well would check them twice.
                if (!customization.takesOver(rule.id()) || !selection.isEnabled(rule)) {
                    continue;
                }
                resolved.add(resolve(configuration, rule, scopeName, scope));
            }
        }
        for (ArchitectureRuleSet ruleSet : customization.additionalRuleSets()) {
            for (ArchitectureRule rule : ruleSet.rules(context)) {
                if (selection.isEnabled(rule)) {
                    resolved.add(resolve(configuration, rule, scopeName, scope));
                }
            }
        }
        for (ArchitectureRule rule : customization.additionalRules()) {
            if (selection.isEnabled(rule)) {
                resolved.add(resolve(configuration, rule, scopeName, scope));
            }
        }
        return resolved;
    }

    private static DescribedPredicate<JavaClass> serviceScope(List<String> excludedPackageIdentifiers) {
        return excludedPackageIdentifiers.isEmpty()
                ? DescribedPredicate.<JavaClass>alwaysTrue().as("in the service")
                : ArchitecturePredicates.residingOutsideOf(excludedPackageIdentifiers)
                .as("in the service outside of the modules customizing this rule "
                        + excludedPackageIdentifiers);
    }

    private static ArchRule applySuiteSettings(ArchitectureRulesConfiguration configuration, ArchRule rule) {
        ArchRule finalized = rule.allowEmptyShould(configuration.allowEmptyShould());
        return configuration.freeze() ? FreezingArchRule.freeze(finalized) : finalized;
    }

    private static void rejectDuplicates(List<ResolvedRule> resolved) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = resolved.stream()
                .map(rule -> rule.scopeName() + "/" + rule.id().value())
                .filter(key -> !seen.add(key))
                .collect(Collectors.toList());
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("Duplicate architecture rule ids in the same scope: " + duplicates
                    + ". Rule ids identify a rule in configuration and in the build report, so two rule sets"
                    + " must not use the same one - give the custom rule its own id.");
        }
    }
}
