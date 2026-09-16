package ru.ludwigandreas.archrules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.tngtech.archunit.core.importer.ImportOption;

import ru.ludwigandreas.archrules.report.ReportingConfiguration;

/**
 * The complete, immutable description of what a service wants checked: which code to analyse, with
 * which conventions, which rules switched on, and which modules deviate.
 *
 * <p>Built either programmatically, from
 * {@link ru.ludwigandreas.archrules.config.ArchitectureRulesProperties a properties file}, or from
 * the {@link ru.ludwigandreas.archrules.junit.AnalyzeArchitecture} annotation - the three layer on
 * top of each other in that order.
 *
 * <pre>{@code
 * ArchitectureRulesConfiguration.builder()
 *         .basePackages("com.acme.orders")
 *         .enable("domain-isolation")
 *         .disable("kafka", "storage")
 *         .build();
 * }</pre>
 */
public final class ArchitectureRulesConfiguration {

    /**
     * Analyse production code only. Test classes are excluded on purpose: they are allowed to reach
     * across layers, and {@link RuleGroup#TEST_SEPARATION} can only mean anything if the test
     * sources are not part of the imported class set to begin with.
     */
    private static final List<ImportOption> DEFAULT_IMPORT_OPTIONS = List.of(
            ImportOption.Predefined.DO_NOT_INCLUDE_TESTS,
            ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
            ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES,
            ImportOption.Predefined.DO_NOT_INCLUDE_PACKAGE_INFOS);

    private final List<String> basePackages;
    private final ArchitectureConventions conventions;
    private final RuleSelection selection;
    private final SeverityPolicy severities;
    private final String serviceName;
    private final List<String> modulePackages;
    private final List<ModuleRuleCustomization> moduleCustomizations;
    private final List<ArchitectureRuleSet> additionalRuleSets;
    private final boolean includeBuiltInRuleSets;
    private final boolean includeServiceLoaderRuleSets;
    private final List<ImportOption> importOptions;
    private final boolean allowEmptyShould;
    private final boolean freeze;
    private final boolean allowEmptyAnalysis;
    private final ReportingConfiguration reporting;

    private ArchitectureRulesConfiguration(Builder builder) {
        if (builder.basePackages.isEmpty()) {
            throw new IllegalStateException("At least one base package is required - "
                    + "set it with basePackages(..), basePackageOf(..), the 'architecture.rules.base-packages'"
                    + " property, or @AnalyzeArchitecture(packagesOf = ...)");
        }
        this.basePackages = List.copyOf(builder.basePackages);
        this.conventions = builder.conventions;
        this.selection = builder.selection;
        this.severities = builder.severities;
        this.serviceName = builder.serviceName != null ? builder.serviceName : this.basePackages.get(0);
        this.modulePackages = List.copyOf(builder.modulePackages);
        this.moduleCustomizations = List.copyOf(builder.moduleCustomizations);
        this.additionalRuleSets = List.copyOf(builder.additionalRuleSets);
        this.includeBuiltInRuleSets = builder.includeBuiltInRuleSets;
        this.includeServiceLoaderRuleSets = builder.includeServiceLoaderRuleSets;
        this.importOptions = List.copyOf(builder.importOptions);
        this.allowEmptyShould = builder.allowEmptyShould;
        this.freeze = builder.freeze;
        this.allowEmptyAnalysis = builder.allowEmptyAnalysis;
        this.reporting = builder.reporting;
        validateModuleCustomizations();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.basePackages.addAll(basePackages);
        builder.conventions = conventions;
        builder.selection = selection;
        builder.severities = severities;
        builder.serviceName = serviceName;
        builder.modulePackages.addAll(modulePackages);
        builder.moduleCustomizations.addAll(moduleCustomizations);
        builder.additionalRuleSets.addAll(additionalRuleSets);
        builder.includeBuiltInRuleSets = includeBuiltInRuleSets;
        builder.includeServiceLoaderRuleSets = includeServiceLoaderRuleSets;
        builder.importOptions.clear();
        builder.importOptions.addAll(importOptions);
        builder.allowEmptyShould = allowEmptyShould;
        builder.freeze = freeze;
        builder.allowEmptyAnalysis = allowEmptyAnalysis;
        builder.reporting = reporting;
        return builder;
    }

    public List<String> basePackages() {
        return basePackages;
    }

    public ArchitectureConventions conventions() {
        return conventions;
    }

    public RuleSelection selection() {
        return selection;
    }

    /** Per-rule severity overrides. */
    public SeverityPolicy severities() {
        return severities;
    }

    /** Name this service reports itself under, so that reports from many services can be pooled. */
    public String serviceName() {
        return serviceName;
    }

    /** Explicitly configured module packages; empty means "discover them from the imported code". */
    public List<String> modulePackages() {
        return modulePackages;
    }

    public List<ModuleRuleCustomization> moduleCustomizations() {
        return moduleCustomizations;
    }

    public List<ArchitectureRuleSet> additionalRuleSets() {
        return additionalRuleSets;
    }

    public boolean includeBuiltInRuleSets() {
        return includeBuiltInRuleSets;
    }

    public boolean includeServiceLoaderRuleSets() {
        return includeServiceLoaderRuleSets;
    }

    public List<ImportOption> importOptions() {
        return importOptions;
    }

    public boolean allowEmptyShould() {
        return allowEmptyShould;
    }

    public boolean freeze() {
        return freeze;
    }

    public boolean allowEmptyAnalysis() {
        return allowEmptyAnalysis;
    }

    /** Which reports the run emits, and where. */
    public ReportingConfiguration reporting() {
        return reporting;
    }

    private void validateModuleCustomizations() {
        Set<String> seen = new LinkedHashSet<>();
        for (ModuleRuleCustomization customization : moduleCustomizations) {
            for (String modulePackage : customization.modulePackages()) {
                if (!seen.add(modulePackage)) {
                    throw new IllegalStateException("Module '" + modulePackage + "' is customized more than once;"
                            + " merge the customizations so that the rules applying to it are unambiguous");
                }
                boolean insideBasePackages = basePackages.stream()
                        .anyMatch(basePackage -> modulePackage.equals(basePackage)
                                || modulePackage.startsWith(basePackage + "."));
                if (!insideBasePackages) {
                    throw new IllegalStateException("Customized module '" + modulePackage
                            + "' lies outside the analysed base packages " + basePackages
                            + ", so none of its rules would ever be evaluated");
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ArchitectureRulesConfiguration{basePackages=" + basePackages
                + ", selection=" + selection
                + ", modules=" + modulePackages
                + ", customizedModules=" + moduleCustomizations
                + ", allowEmptyShould=" + allowEmptyShould
                + ", freeze=" + freeze + '}';
    }

    /** Builder for {@link ArchitectureRulesConfiguration}. */
    public static final class Builder {

        private final List<String> basePackages = new ArrayList<>();
        private ArchitectureConventions conventions = ArchitectureConventions.defaults();
        private RuleSelection selection = RuleSelection.none();
        private SeverityPolicy severities = SeverityPolicy.none();
        private String serviceName;
        private final List<String> modulePackages = new ArrayList<>();
        private final List<ModuleRuleCustomization> moduleCustomizations = new ArrayList<>();
        private final List<ArchitectureRuleSet> additionalRuleSets = new ArrayList<>();
        private boolean includeBuiltInRuleSets = true;
        private boolean includeServiceLoaderRuleSets = true;
        private final List<ImportOption> importOptions = new ArrayList<>(DEFAULT_IMPORT_OPTIONS);
        private boolean allowEmptyShould = true;
        private boolean freeze;
        private boolean allowEmptyAnalysis;
        private ReportingConfiguration reporting = ReportingConfiguration.defaults();

        private Builder() {
        }

        /** Root packages of the service under analysis, e.g. {@code "com.acme.orders"}. */
        public Builder basePackages(String... packages) {
            return basePackages(Arrays.asList(packages));
        }

        public Builder basePackages(Collection<String> packages) {
            Objects.requireNonNull(packages, "packages");
            for (String basePackage : packages) {
                if (basePackage == null || basePackage.isBlank()) {
                    throw new IllegalArgumentException("Base package must not be blank");
                }
                String normalized = basePackage.trim();
                if (normalized.endsWith("..") || normalized.contains("*")) {
                    throw new IllegalArgumentException("Base package must be a plain package name such as"
                            + " 'com.acme', not a package identifier: " + basePackage);
                }
                if (!basePackages.contains(normalized)) {
                    basePackages.add(normalized);
                }
            }
            return this;
        }

        /** Takes the base package from a class, typically the Spring Boot application class. */
        public Builder basePackageOf(Class<?>... classes) {
            Objects.requireNonNull(classes, "classes");
            for (Class<?> type : classes) {
                basePackages(type.getPackageName());
            }
            return this;
        }

        public Builder conventions(ArchitectureConventions newConventions) {
            this.conventions = Objects.requireNonNull(newConventions, "conventions");
            return this;
        }

        /** Derives the conventions from the ones configured so far. */
        public Builder conventions(java.util.function.UnaryOperator<ArchitectureConventions.Builder> customizer) {
            Objects.requireNonNull(customizer, "customizer");
            this.conventions = customizer.apply(conventions.toBuilder()).build();
            return this;
        }

        /** Switches rules on. Accepts group ids, rule ids or {@code *}. */
        public Builder enable(String... selectors) {
            this.selection = selection.enable(selectors);
            return this;
        }

        /** Switches rules off. Accepts group ids, rule ids or {@code *}. */
        public Builder disable(String... selectors) {
            this.selection = selection.disable(selectors);
            return this;
        }

        /** Reports violations of these rules without failing the build. */
        public Builder warnOn(String... selectors) {
            this.severities = severities.with(RuleSeverity.WARNING, selectors);
            return this;
        }

        /** Restores build-failing severity for these rules. */
        public Builder failOn(String... selectors) {
            this.severities = severities.with(RuleSeverity.ERROR, selectors);
            return this;
        }

        public Builder severities(SeverityPolicy policy) {
            this.severities = severities.mergedWith(Objects.requireNonNull(policy, "policy"));
            return this;
        }

        /**
         * The name this service is known by in the aggregated reports. Defaults to the first base
         * package, which is unique enough to pool by but rarely what a dashboard wants to show.
         */
        public Builder serviceName(String name) {
            this.serviceName = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder selection(RuleSelection additionalSelection) {
            this.selection = selection.mergedWith(Objects.requireNonNull(additionalSelection, "additionalSelection"));
            return this;
        }

        /**
         * Declares the module/bounded-context packages explicitly. When left empty they are
         * discovered from the imported classes as the direct sub-packages of the base packages.
         */
        public Builder modules(String... packages) {
            return modules(Arrays.asList(packages));
        }

        public Builder modules(Collection<String> packages) {
            Objects.requireNonNull(packages, "packages");
            for (String modulePackage : packages) {
                if (modulePackage == null || modulePackage.isBlank()) {
                    throw new IllegalArgumentException("Module package must not be blank");
                }
                String normalized = modulePackage.trim();
                if (!modulePackages.contains(normalized)) {
                    modulePackages.add(normalized);
                }
            }
            return this;
        }

        /** Adds conventions, toggles or rules that apply to one module or a list of modules. */
        public Builder customizeModules(ModuleRuleCustomization customization) {
            moduleCustomizations.add(Objects.requireNonNull(customization, "customization"));
            return this;
        }

        /** Adds a rule set of the service's own, checked alongside the built-in ones. */
        public Builder addRuleSet(ArchitectureRuleSet ruleSet) {
            additionalRuleSets.add(Objects.requireNonNull(ruleSet, "ruleSet"));
            return this;
        }

        /** Adds a single pre-built rule under {@link RuleGroup#CUSTOM} or any group of choice. */
        public Builder addRule(ArchitectureRule rule) {
            Objects.requireNonNull(rule, "rule");
            return addRuleSet(new SingleRuleSet(rule));
        }

        /**
         * Turns the library's own rule sets off, leaving only the ones the service registered. Meant
         * for services that want this library purely as an execution and configuration harness.
         */
        public Builder includeBuiltInRuleSets(boolean include) {
            this.includeBuiltInRuleSets = include;
            return this;
        }

        /** Whether rule sets published through {@code ServiceLoader} are picked up. Default true. */
        public Builder includeServiceLoaderRuleSets(boolean include) {
            this.includeServiceLoaderRuleSets = include;
            return this;
        }

        /** Replaces the import options, e.g. to analyse test sources as well. */
        public Builder importOptions(ImportOption... options) {
            return importOptions(Arrays.asList(options));
        }

        public Builder importOptions(Collection<ImportOption> options) {
            Objects.requireNonNull(options, "options");
            importOptions.clear();
            importOptions.addAll(options);
            return this;
        }

        public Builder addImportOption(ImportOption option) {
            importOptions.add(Objects.requireNonNull(option, "option"));
            return this;
        }

        /**
         * Whether a rule that matches no class at all passes (default) or fails. Keeping it on is
         * what lets a service enable, say, the Kafka rules before it has any Kafka code.
         */
        public Builder allowEmptyShould(boolean allow) {
            this.allowEmptyShould = allow;
            return this;
        }

        /**
         * Records today's violations as the accepted baseline and fails only on new ones
         * (ArchUnit's {@code FreezingArchRule}). The way to introduce these rules into an existing
         * codebase without a big-bang cleanup. Off by default.
         */
        public Builder freeze(boolean enabled) {
            this.freeze = enabled;
            return this;
        }

        /**
         * Whether an analysis that imported no class at all is acceptable. It is not, by default:
         * a mistyped base package would otherwise let every rule pass vacuously and report a green
         * architecture test for a service nothing was ever checked in.
         */
        public Builder allowEmptyAnalysis(boolean allow) {
            this.allowEmptyAnalysis = allow;
            return this;
        }

        /** Replaces the reporting settings outright. */
        public Builder reporting(ReportingConfiguration reportingConfiguration) {
            this.reporting = Objects.requireNonNull(reportingConfiguration, "reportingConfiguration");
            return this;
        }

        /**
         * Adjusts the reporting settings, e.g.
         * {@code reporting(r -> r.jsonFile(Path.of("target/arch.json")).maxViolationsPerRule(10))}.
         */
        public Builder reporting(java.util.function.UnaryOperator<ReportingConfiguration.Builder> customizer) {
            Objects.requireNonNull(customizer, "customizer");
            this.reporting = customizer.apply(reporting.toBuilder()).build();
            return this;
        }

        public ArchitectureRulesConfiguration build() {
            return new ArchitectureRulesConfiguration(this);
        }
    }

    /** Wraps a single pre-built rule so it can travel through the same rule set pipeline. */
    private record SingleRuleSet(ArchitectureRule rule) implements ArchitectureRuleSet {

        @Override
        public RuleGroup group() {
            return rule.group();
        }

        @Override
        public List<ArchitectureRule> rules(RuleContext context) {
            return List.of(rule);
        }

        @Override
        public String name() {
            return "rule(" + rule.id().value() + ")";
        }
    }
}
