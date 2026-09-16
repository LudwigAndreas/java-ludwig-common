package ru.ludwigandreas.archrules.rules;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.ConventionSetting;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * Every REST controller is mounted under the same shape of base path.
 *
 * <p>An organisation-wide rule rather than a per-service preference: the gateway routes on the path
 * prefix, the version segment is what makes a breaking change expressible, and a client that has
 * learned {@code /api/v1/...} from one service should not meet {@code /orders/v2} in the next. One
 * service publishing {@code /internal/orders} is enough to make the routing rules special-case it
 * forever.
 *
 * <p>Implemented as a condition over the annotation's member values - the path is a string inside
 * {@code @RequestMapping}, which no dependency rule can see. The pattern itself is configuration
 * ({@link ConventionSetting#REST_BASE_PATH_PATTERN}), so an organisation with a different convention
 * changes one property instead of forking the rule.
 */
public final class RestPathRules implements ArchitectureRuleSet {

    /** Every {@code @RestController} declares a base path matching the configured pattern. */
    public static final RuleId CONTROLLERS_DECLARE_VERSIONED_BASE_PATH =
            RuleId.of(RuleGroup.REST_PATHS, "controllers-declare-a-versioned-base-path");

    @Override
    public RuleGroup group() {
        return RuleGroup.REST_PATHS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        DescribedPredicate<JavaClass> restControllers =
                ConventionPredicates.annotatedAs(context, AnnotationRole.REST_CONTROLLER).as("REST controllers");
        Optional<String> configuredPattern = context.setting(ConventionSetting.REST_BASE_PATH_PATTERN);
        if (configuredPattern.isEmpty()) {
            // Cleared rather than customized. Fail the controllers this would have checked, with the
            // property to set - rather than throwing here, which would take the whole run (and both
            // reports) down over one unconfigured rule.
            return List.of(ArchitectureRule.of(CONTROLLERS_DECLARE_VERSIONED_BASE_PATH,
                    ArchRuleDefinition.classes()
                            .that(restControllers)
                            .should(ArchitectureConditions.alwaysViolate("be checked against a configured path pattern",
                                    "No REST base path pattern is configured. Set"
                                            + " 'architecture.rules.conventions.settings."
                                            + ConventionSetting.REST_BASE_PATH_PATTERN.id()
                                            + " = /api/v\\d+(/.*)?', or switch the rule off with"
                                            + " 'architecture.rules.rules."
                                            + CONTROLLERS_DECLARE_VERSIONED_BASE_PATH.value() + " = false'"))
                            .as("REST controllers declare a base path matching the configured pattern"
                                    + " (not configured yet)"),
                    "Configure the base path pattern the organisation uses, or disable this rule."));
        }
        String pattern = configuredPattern.get();
        Pattern compiled = compile(pattern);
        ArchRule rule = ArchRuleDefinition.classes()
                .that(restControllers)
                .should(ArchitectureConditions.haveBasePathMatching(
                        context.annotations(AnnotationRole.REQUEST_MAPPING), compiled))
                .as("REST controllers declare a base path matching '" + pattern + "'");
        return List.of(ArchitectureRule.of(CONTROLLERS_DECLARE_VERSIONED_BASE_PATH, rule,
                "Annotate the controller class with @RequestMapping(\"/api/v1/<resource>\") - or move the"
                        + " existing path onto the class - so the whole estate keeps one versioned path shape."
                        + " The expected shape is '" + pattern + "'; if this service genuinely differs, change"
                        + " the pattern in 'architecture.rules.conventions.settings."
                        + ConventionSetting.REST_BASE_PATH_PATTERN.id() + "' rather than exempting the class."));
    }

    private static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException invalid) {
            throw new IllegalStateException("The configured REST base path pattern '" + pattern
                    + "' is not a valid regular expression", invalid);
        }
    }
}
