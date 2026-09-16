package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;

/**
 * No package cycles - between the modules of the service, and inside each module.
 *
 * <p>Checked on every build rather than occasionally, because a cycle is never introduced
 * deliberately: it appears when someone adds one convenient import, and by the time anyone notices,
 * untangling it is a refactoring instead of a one-line change.
 *
 * <p>Two levels, because they fail for different reasons and deserve separate failures. The
 * module-level slice ({@code com.acme.(*)..}) catches two bounded contexts that have grown into each
 * other; the per-module slice ({@code com.acme.orders.(*)..}) catches a module whose own layers have
 * started referring back to each other. The second is generated once per module, so its rule id
 * carries the module name: {@code cycles.module-internals-are-free-of-cycles[com.acme.orders]}.
 */
public final class CycleRules implements ArchitectureRuleSet {

    /** No cycles between the modules/bounded contexts of the service. */
    public static final RuleId MODULES = RuleId.of(RuleGroup.CYCLES, "modules-are-free-of-cycles");

    /**
     * No cycles between the packages inside one module. Generated once per module, so the id that
     * addresses a single module carries its package as a qualifier.
     */
    public static final RuleId MODULE_INTERNALS = RuleId.of(RuleGroup.CYCLES, "module-internals-are-free-of-cycles");

    @Override
    public RuleGroup group() {
        return RuleGroup.CYCLES;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        boolean qualifyByBasePackage = context.basePackages().size() > 1;
        for (String basePackage : context.basePackages()) {
            RuleId id = qualifyByBasePackage
                    ? RuleId.of(RuleGroup.CYCLES, MODULES.name(), basePackage)
                    : MODULES;
            rules.add(ArchitectureRule.of(id, freeOfCycles(basePackage, "modules of " + basePackage),
                    "Break the cycle between the modules: move the shared type into a module both can"
                            + " depend on, or invert one direction with an interface owned by the module being"
                            + " called. Two modules in a cycle cannot be released, tested or extracted apart."));
        }
        for (String modulePackage : context.modulePackages()) {
            rules.add(ArchitectureRule.of(RuleId.of(RuleGroup.CYCLES, MODULE_INTERNALS.name(), modulePackage),
                    freeOfCycles(modulePackage, "packages of module " + modulePackage),
                    "Break the cycle inside the module: the packages listed in the violation refer back to"
                            + " each other, usually because one convenience import crossed a layer. Move the"
                            + " shared type to the layer both sides already depend on."));
        }
        return List.copyOf(rules);
    }

    private static ArchRule freeOfCycles(String rootPackage, String description) {
        return SlicesRuleDefinition.slices()
                .matching(rootPackage + ".(*)..")
                .as(description)
                .should().beFreeOfCycles();
    }
}
