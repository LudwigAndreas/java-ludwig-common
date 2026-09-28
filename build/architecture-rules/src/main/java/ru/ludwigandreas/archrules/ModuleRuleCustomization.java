package ru.ludwigandreas.archrules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Rules and conventions that apply to one module - or to a list of modules - instead of to the whole
 * service.
 *
 * <p>Two things can be said per module: <em>different</em> (a package convention or a toggle that
 * deviates from the service-wide one) and <em>more</em> (extra rules that only this module has to
 * satisfy).
 *
 * <pre>{@code
 * ArchitectureRulesConfiguration.builder()
 *         .basePackages("com.acme")
 *         // the legacy module keeps its entities in ..jpa.. and is not ready for the strict
 *         // controller/persistence separation yet
 *         .customizeModules(ModuleRuleCustomization.forModules("com.acme.legacy")
 *                 .conventions(conventions -> conventions.addPackages(PackageRole.ENTITY, "..jpa.."))
 *                 .disable("layering.controllers-do-not-access-persistence")
 *                 .build())
 *         // the two payment modules must additionally keep the domain framework-free
 *         .customizeModules(ModuleRuleCustomization.forModules("com.acme.payments", "com.acme.payouts")
 *                 .enable("domain-isolation")
 *                 .addRuleSet(new PciAuditRules())
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <h2>How a customization interacts with the service-wide rules</h2>
 * A customization <em>takes over</em> a rule when it changes the conventions (which changes what
 * every rule means) or when its selection addresses that rule. A taken-over rule is removed from the
 * service-wide scope for this module's classes and re-created in a module scope with the module's
 * own conventions and toggles, so a module is never checked twice or checked by both variants.
 * Rules the customization says nothing about keep applying service-wide, module classes included.
 */
public final class ModuleRuleCustomization {

    private final List<String> modulePackages;
    private final UnaryOperator<ArchitectureConventions> conventionsCustomizer;
    private final RuleSelection selection;
    private final List<ArchitectureRuleSet> additionalRuleSets;
    private final List<ArchitectureRule> additionalRules;

    private ModuleRuleCustomization(Builder builder) {
        this.modulePackages = List.copyOf(builder.modulePackages);
        this.conventionsCustomizer = builder.conventionsCustomizer;
        this.selection = builder.selection;
        this.additionalRuleSets = List.copyOf(builder.additionalRuleSets);
        this.additionalRules = List.copyOf(builder.additionalRules);
    }

    /**
     * Starts a customization for the given module packages, e.g.
     * {@code forModules("com.acme.orders", "com.acme.billing")}. The packages are fully qualified
     * module roots, not ArchUnit package identifiers - everything below them belongs to the module.
     */
    public static Builder forModules(String... modulePackages) {
        return forModules(Arrays.asList(modulePackages));
    }

    public static Builder forModules(Collection<String> modulePackages) {
        return new Builder(modulePackages);
    }

    public List<String> modulePackages() {
        return modulePackages;
    }

    public RuleSelection selection() {
        return selection;
    }

    public List<ArchitectureRuleSet> additionalRuleSets() {
        return additionalRuleSets;
    }

    public List<ArchitectureRule> additionalRules() {
        return additionalRules;
    }

    /** Whether this customization redefines what the rules mean for its modules. */
    public boolean overridesConventions() {
        return conventionsCustomizer != null;
    }

    /** The conventions in force inside these modules, derived from the service-wide ones. */
    public ArchitectureConventions conventionsFrom(ArchitectureConventions serviceWide) {
        Objects.requireNonNull(serviceWide, "serviceWide");
        return conventionsCustomizer == null ? serviceWide : conventionsCustomizer.apply(serviceWide);
    }

    /**
     * Whether the service-wide instance of the given rule must step aside for these modules, because
     * this customization either redefines the conventions the rule is built from or addresses the
     * rule by name.
     */
    public boolean takesOver(RuleId ruleId) {
        return overridesConventions() || selection.hasAnyOverrideFor(ruleId);
    }

    /** ArchUnit package identifiers covering everything inside these modules. */
    public List<String> modulePackageIdentifiers() {
        return modulePackages.stream().map(modulePackage -> modulePackage + "..").toList();
    }

    @Override
    public String toString() {
        return "ModuleRuleCustomization" + modulePackages;
    }

    /** Builder for {@link ModuleRuleCustomization}. */
    public static final class Builder {

        private final List<String> modulePackages;
        private UnaryOperator<ArchitectureConventions> conventionsCustomizer;
        private RuleSelection selection = RuleSelection.none();
        private final List<ArchitectureRuleSet> additionalRuleSets = new ArrayList<>();
        private final List<ArchitectureRule> additionalRules = new ArrayList<>();

        private Builder(Collection<String> modulePackages) {
            Objects.requireNonNull(modulePackages, "modulePackages");
            if (modulePackages.isEmpty()) {
                throw new IllegalArgumentException("A module customization needs at least one module package");
            }
            this.modulePackages = new ArrayList<>();
            for (String modulePackage : modulePackages) {
                if (modulePackage == null || modulePackage.isBlank()) {
                    throw new IllegalArgumentException("Module package must not be blank");
                }
                if (modulePackage.contains("*") || modulePackage.endsWith("..")) {
                    throw new IllegalArgumentException("Module package must be a plain package name such as"
                            + " 'com.acme.orders', not a package identifier: " + modulePackage);
                }
                this.modulePackages.add(modulePackage.trim());
            }
        }

        /** Derives the module's conventions from the service-wide ones. */
        public Builder conventions(UnaryOperator<ArchitectureConventions.Builder> customizer) {
            Objects.requireNonNull(customizer, "customizer");
            this.conventionsCustomizer = serviceWide -> customizer.apply(serviceWide.toBuilder()).build();
            return this;
        }

        /** Replaces the module's conventions outright, ignoring the service-wide ones. */
        public Builder conventions(ArchitectureConventions conventions) {
            Objects.requireNonNull(conventions, "conventions");
            this.conventionsCustomizer = serviceWide -> conventions;
            return this;
        }

        /** Switches rules on for these modules only. Accepts group ids, rule ids or {@code *}. */
        public Builder enable(String... selectors) {
            this.selection = selection.enable(selectors);
            return this;
        }

        /** Switches rules off for these modules only. Accepts group ids, rule ids or {@code *}. */
        public Builder disable(String... selectors) {
            this.selection = selection.disable(selectors);
            return this;
        }

        public Builder selection(RuleSelection moduleSelection) {
            this.selection = selection.mergedWith(Objects.requireNonNull(moduleSelection, "moduleSelection"));
            return this;
        }

        /** Adds a rule set that only these modules have to satisfy. */
        public Builder addRuleSet(ArchitectureRuleSet ruleSet) {
            additionalRuleSets.add(Objects.requireNonNull(ruleSet, "ruleSet"));
            return this;
        }

        /** Adds a single pre-built rule that only these modules have to satisfy. */
        public Builder addRule(ArchitectureRule rule) {
            additionalRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public ModuleRuleCustomization build() {
            return new ModuleRuleCustomization(this);
        }
    }
}
