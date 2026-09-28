package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Objects;

/**
 * Everything a {@link ArchitectureRuleSet} needs in order to build its rules for one scope: the
 * packages that make up the code under analysis, the {@link ArchitectureConventions} in force there,
 * and the modules discovered inside it.
 *
 * <p>A suite has one context for the service as a whole and one more per module that overrides
 * something (see {@link ModuleRuleCustomization}), which is why rule sets must read their package
 * names from the context rather than from a static constant.
 *
 * @param scopeName      human-readable name of the scope, used in rule descriptions and test names
 * @param basePackages   root packages of the code under analysis, e.g. {@code [com.acme.orders]}
 * @param modulePackages fully qualified module/bounded-context packages inside the base packages
 * @param conventions    the package/annotation/type vocabulary in force for this scope
 */
public record RuleContext(String scopeName,
                          List<String> basePackages,
                          List<String> modulePackages,
                          ArchitectureConventions conventions) {

    public RuleContext {
        Objects.requireNonNull(scopeName, "scopeName");
        Objects.requireNonNull(conventions, "conventions");
        basePackages = List.copyOf(Objects.requireNonNull(basePackages, "basePackages"));
        modulePackages = List.copyOf(Objects.requireNonNull(modulePackages, "modulePackages"));
        if (basePackages.isEmpty()) {
            throw new IllegalArgumentException("At least one base package is required");
        }
    }

    /** Package identifiers of the given role, e.g. {@code [..controller.., ..web..]}. */
    public List<String> packages(PackageRole role) {
        return conventions.packages(role);
    }

    /** Package identifiers of the given role as the array ArchUnit's package predicates take. */
    public String[] packageArray(PackageRole role) {
        return conventions.packages(role).toArray(String[]::new);
    }

    public List<String> libraryPackages(ExternalLibrary library) {
        return conventions.libraryPackages(library);
    }

    public List<String> annotations(AnnotationRole role) {
        return conventions.annotations(role);
    }

    public List<String> types(TypeRole role) {
        return conventions.types(role);
    }

    public List<String> settings(ConventionSetting setting) {
        return conventions.settings(setting);
    }

    public java.util.Optional<String> setting(ConventionSetting setting) {
        return conventions.setting(setting);
    }

    /** Whether the consuming service configured the type names a rule needs to be able to run. */
    public boolean hasTypesFor(TypeRole role) {
        return !conventions.types(role).isEmpty();
    }

    /** {@code [com.acme..]} - the base packages as recursive package identifiers. */
    public List<String> basePackageIdentifiers() {
        return basePackages.stream().map(basePackage -> basePackage + "..").toList();
    }

    /** Whether the scope declares any package for the role, i.e. whether role-specific rules apply. */
    public boolean hasPackagesFor(PackageRole role) {
        return !conventions.packages(role).isEmpty();
    }
}
