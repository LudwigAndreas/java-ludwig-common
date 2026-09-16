package ru.ludwigandreas.archrules.rules;

import java.util.List;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * Typed configuration is validated, so a bad value stops the pod instead of travelling.
 *
 * <p>{@code @ConfigurationProperties} without {@code @Validated} binds whatever the environment
 * provides: a missing ConfigMap key becomes {@code null}, a misspelled number becomes a default, and
 * the failure surfaces much later as a {@code NullPointerException} or a call to the wrong host. With
 * {@code @Validated} and the constraints on the properties class, the context refuses to start and
 * the deployment rolls back with the offending property named - which, on Kubernetes, is the
 * difference between a failed rollout and a half-broken service that passes its readiness probe.
 */
public final class ConfigurationPropertiesRules implements ArchitectureRuleSet {

    /** Every {@code @ConfigurationProperties} class is also {@code @Validated}. */
    public static final RuleId PROPERTIES_ARE_VALIDATED =
            RuleId.of(RuleGroup.CONFIGURATION_PROPERTIES, "configuration-properties-are-validated");

    @Override
    public RuleGroup group() {
        return RuleGroup.CONFIGURATION_PROPERTIES;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        ArchRule rule = ArchRuleDefinition.classes()
                .that(ConventionPredicates.annotatedAs(context, AnnotationRole.CONFIGURATION_PROPERTIES)
                        .as("@ConfigurationProperties classes"))
                .should(ArchitectureConditions.beAnnotatedWithAny(context.annotations(AnnotationRole.VALIDATED),
                        "@Validated"))
                .as("Configuration properties are validated");
        return List.of(ArchitectureRule.of(PROPERTIES_ARE_VALIDATED, rule,
                "Add @org.springframework.validation.annotation.Validated to the properties class and put"
                        + " constraints on its fields (@NotBlank, @Min, @Positive, @DurationMin ...), so an"
                        + " invalid ConfigMap or environment value fails the context start instead of"
                        + " surfacing later as a null or a default."));
    }
}
