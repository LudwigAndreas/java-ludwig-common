package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.TypeRole;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;

/**
 * Builds the rules that need a name only the consuming service can supply - the base exception class,
 * the base entity, the producer interface.
 *
 * <p>A missing name is neither guessed nor silently skipped. The rule is built anyway, selecting
 * exactly the classes the real check would have applied to, and failing them with an instruction:
 * the service that has no Kafka producer, no entity or no custom exception stays green, and the
 * service that has them is told which property to set or which rule to disable. Silently skipping
 * would leave a rule that reports success while checking nothing, which is the failure mode this
 * library exists to prevent.
 */
final class RequiredConfiguration {

    private RequiredConfiguration() {
    }

    /**
     * "These classes must extend the configured base type", or the instruction to configure it.
     *
     * @param subjects            the classes the rule applies to
     * @param subjectDescription  how those classes are described in the rule text
     * @param propertyKey         the property that supplies the missing name
     * @param example             a plausible value, shown in the failure message
     * @param ruleId              the rule id, shown in the failure message as the thing to disable
     */
    static ArchRule mustExtendConfiguredType(RuleContext context,
                                             TypeRole role,
                                             DescribedPredicate<JavaClass> subjects,
                                             String subjectDescription,
                                             String propertyKey,
                                             String example,
                                             String ruleId) {
        if (context.hasTypesFor(role)) {
            return ArchRuleDefinition.classes()
                    .that(subjects)
                    .should(ArchitectureConditions.beAssignableToAny(context.types(role),
                            "the configured " + role.id() + " " + context.types(role)))
                    .as(subjectDescription + " extend " + context.types(role));
        }
        return ArchRuleDefinition.classes()
                .that(subjects)
                .should(ArchitectureConditions.alwaysViolate(
                        "be checked against a configured " + role.id(),
                        missingConfigurationMessage(role, propertyKey, example, ruleId)))
                .as(subjectDescription + " extend the configured " + role.id() + " (not configured yet)");
    }

    static String missingConfigurationMessage(TypeRole role, String propertyKey, String example, String ruleId) {
        return "No " + role.id() + " type is configured, so this class cannot be checked."
                + " Set '" + propertyKey + " = " + example + "'"
                + " (or conventions(c -> c.types(TypeRole." + role.name() + ", \"" + example + "\")) in code),"
                + " or switch the rule off with 'architecture.rules.rules." + ruleId + " = false'";
    }
}
