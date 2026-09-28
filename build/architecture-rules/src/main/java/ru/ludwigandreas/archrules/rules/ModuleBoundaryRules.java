package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;

/**
 * Module boundaries in a package-by-feature codebase: what a module publishes, and what it keeps to
 * itself.
 *
 * <p>Without an enforced boundary a "module" is a naming convention. The moment one feature imports
 * another's repository, the two share a schema, a release and a rollback, and the modules can no
 * longer be reasoned about - or later extracted - separately. Marking the inside of a module
 * {@code internal} says where that line runs; this rule is what makes the line real.
 *
 * <p>Cycles <em>between</em> modules are the other half of the same boundary and are checked by
 * {@link CycleRules} ({@code cycles.modules-are-free-of-cycles}), which is where slicing belongs.
 */
public final class ModuleBoundaryRules implements ArchitectureRuleSet {

    /** Nothing reaches into another module's {@code internal} packages. */
    public static final RuleId INTERNALS_ARE_PRIVATE =
            RuleId.of(RuleGroup.MODULE_BOUNDARY, "internals-are-not-accessed-from-other-modules");

    /** Cross-module dependencies go through the module's {@code api} package only. Opt-in. */
    public static final RuleId CROSS_MODULE_ACCESS_THROUGH_API =
            RuleId.of(RuleGroup.MODULE_BOUNDARY, "cross-module-access-goes-through-the-api-package");

    @Override
    public RuleGroup group() {
        return RuleGroup.MODULE_BOUNDARY;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        if (context.hasPackagesFor(PackageRole.MODULE_INTERNAL)) {
            rules.add(ArchitectureRule.of(INTERNALS_ARE_PRIVATE, internalsArePrivate(context),
                    "Depend on the other module's public API instead of reaching into its internals - if the"
                            + " type you need is not published, add it to that module's API package"
                            + " deliberately, which is the point where someone decides it is a contract."));
        }
        if (context.hasPackagesFor(PackageRole.MODULE_API) && !context.modulePackages().isEmpty()) {
            rules.add(ArchitectureRule.optIn(CROSS_MODULE_ACCESS_THROUGH_API, crossModuleAccessThroughApi(context),
                    "Route the dependency through the other module's api package; everything else it"
                            + " contains is implementation detail."));
        }
        return List.copyOf(rules);
    }

    /**
     * The owning module is derived from where the {@code internal} segment sits, so nothing has to
     * be enumerated: {@code com.acme.orders.internal.jpa} belongs to {@code com.acme.orders}, and
     * only classes under {@code com.acme.orders} may depend on it.
     */
    private static ArchRule internalsArePrivate(RuleContext context) {
        List<String> segments = context.packages(PackageRole.MODULE_INTERNAL);
        ArchCondition<JavaClass> condition = ArchitectureConditions
                .notDependOnInternalsOfOtherModules(context.basePackages(), segments.get(0));
        for (String segment : segments.subList(1, segments.size())) {
            condition = condition.and(
                    ArchitectureConditions.notDependOnInternalsOfOtherModules(context.basePackages(), segment));
        }
        return ArchRuleDefinition.classes()
                .should(condition)
                .as("A module's internals are private to that module");
    }

    /**
     * The stricter, whitelist-shaped variant: instead of marking what is private, a module declares
     * what is public and everything else becomes unreachable from outside. Opt-in, because it only
     * works once every module actually has an API package.
     */
    private static ArchRule crossModuleAccessThroughApi(RuleContext context) {
        return ArchRuleDefinition.classes()
                .should(ArchitectureConditions.onlyDependOnOtherModulesThroughTheirApi(
                        context.modulePackages(), context.packages(PackageRole.MODULE_API)))
                .as("Modules are reached through their API package only");
    }
}
