package ru.ludwigandreas.archrules;

import java.util.List;

/**
 * A family of related rules, built for one scope. This is the extension point of the library: a
 * service that needs a convention of its own implements this interface and registers the
 * implementation - either programmatically via
 * {@link ArchitectureRulesConfiguration.Builder#addRuleSet(ArchitectureRuleSet)}, for one or more
 * modules via {@link ModuleRuleCustomization}, or globally through the {@code ServiceLoader} by
 * listing it in {@code META-INF/services/ru.ludwigandreas.archrules.ArchitectureRuleSet}.
 *
 * <p>Implementations must be stateless and must derive every package name from the
 * {@link RuleContext} they are handed, so that the same rule set can be reused unchanged by a
 * service with a different package layout.
 *
 * <pre>{@code
 * public final class FeatureToggleRules implements ArchitectureRuleSet {
 *
 *     public RuleGroup group() {
 *         return RuleGroup.CUSTOM;
 *     }
 *
 *     public List<ArchitectureRule> rules(RuleContext context) {
 *         return List.of(ArchitectureRule.of(
 *                 RuleId.of(RuleGroup.CUSTOM, "toggles-read-through-registry"),
 *                 noClasses().that().resideOutsideOfPackages("..toggles..")
 *                         .should().dependOnClassesThat().haveFullyQualifiedName("com.acme.Toggles")));
 *     }
 * }
 * }</pre>
 */
public interface ArchitectureRuleSet {

    /** The group every rule of this set belongs to; also the coarse on/off switch for the set. */
    RuleGroup group();

    /**
     * Builds the rules for the given scope. May return an empty list when the scope has nothing for
     * this rule set to check - for instance when the conventions declare no domain packages.
     */
    List<ArchitectureRule> rules(RuleContext context);

    /** Name used in diagnostics. Defaults to the implementation's simple class name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
