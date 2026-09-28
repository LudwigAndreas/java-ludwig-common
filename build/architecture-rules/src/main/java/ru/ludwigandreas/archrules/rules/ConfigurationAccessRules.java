package ru.ludwigandreas.archrules.rules;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * Configuration enters the application in one place.
 *
 * <p>This matters more than usual on Kubernetes, where configuration arrives as environment
 * variables and mounted ConfigMaps. A {@code System.getenv("FEATURE_X")} buried in a service method
 * is invisible to the deployment manifest review, cannot be validated at startup, has no default, no
 * type and no documentation, and behaves differently in a test that cannot set it. Reading it in a
 * {@code @ConfigurationProperties} class instead makes the service's full configuration surface
 * enumerable - and makes a missing variable fail the pod's startup instead of the first request that
 * happens to need it.
 */
public final class ConfigurationAccessRules implements ArchitectureRuleSet {

    /** Only configuration classes read the environment or system properties. */
    public static final RuleId ENVIRONMENT_ACCESS_IS_CONFINED =
            RuleId.of(RuleGroup.CONFIGURATION_ACCESS, "environment-is-read-only-by-configuration-classes");

    private static final Map<String, Set<String>> ENVIRONMENT_ACCESSORS = Map.of(
            "java.lang.System", Set.of("getenv", "getProperty", "getProperties", "setProperty", "clearProperty"));

    @Override
    public RuleGroup group() {
        return RuleGroup.CONFIGURATION_ACCESS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(ArchitectureRule.of(ENVIRONMENT_ACCESS_IS_CONFINED, ArchRuleDefinition.classes()
                .that(DescribedPredicate.not(ConventionPredicates.configurationClasses(context))
                        .as("classes that are not configuration classes"))
                .should(ArchitectureConditions.notCallMethods(ENVIRONMENT_ACCESSORS,
                        "System.getenv/getProperty - receive configuration through injected properties"))
                .as("The environment is read only by configuration classes"),
                "Bind the value in a @ConfigurationProperties class and inject that here. On Kubernetes the"
                        + " configuration arrives as env vars and ConfigMaps, and a getenv buried in business"
                        + " code has no default, no type, no validation and no way to fail the pod at startup."));
    }
}
