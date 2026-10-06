package ru.ludwigandreas.archrules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.core.importer.ImportOption;


/**
 * Builds configurations against the fixture services and reports which rules they break.
 *
 * <p>The fixtures live in {@code src/test/java}, so the import options have to be widened: the
 * production default deliberately excludes test sources.
 */
final class Fixtures {

    static final String ROOT = "ru.ludwigandreas.archrules.fixture";

    private Fixtures() {
    }

    static ArchitectureRulesConfiguration.Builder configurationFor(String fixture) {
        return ArchitectureRulesConfiguration.builder()
                .basePackages(ROOT + "." + fixture)
                .serviceName(fixture)
                // the fixtures are test sources, which the production import options exclude by design
                .importOptions(ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
                        ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES,
                        ImportOption.Predefined.DO_NOT_INCLUDE_PACKAGE_INFOS)
                // no report files and no console noise unless a test asks for them
                .reporting(report -> report.console(false).json(false));
    }

    /**
     * The names a consuming service has to supply for the rules that check against a shared base
     * type. Without them those rules report "not configured" instead of checking anything, which is
     * itself covered by {@link RuleEvaluationTest}.
     */
    static ArchitectureRulesConfiguration.Builder withSharedTypes(ArchitectureRulesConfiguration.Builder builder,
                                                                  String fixture,
                                                                  String baseEntity,
                                                                  String baseException,
                                                                  String eventPublisher) {
        String root = ROOT + "." + fixture;
        return builder.conventions(conventions -> conventions
                .types(TypeRole.BASE_ENTITY, root + "." + baseEntity)
                .types(TypeRole.BASE_EXCEPTION, root + "." + baseException)
                .types(TypeRole.EVENT_PUBLISHER, root + "." + eventPublisher));
    }

    /**
     * The violation messages one rule reports against the given configuration.
     *
     * <p>Needed where a rule has to be shown both firing and <em>not</em> firing in the same run: the
     * id alone says the rule failed, and the interesting claim is which classes it named.
     */
    static List<String> violationDescriptions(ArchitectureRulesConfiguration configuration, RuleId id) {
        ArchitectureRuleSuite suite = ArchitectureRules.suite(configuration);
        for (ResolvedRule rule : suite.rules()) {
            if (rule.id().equals(id)) {
                return rule.evaluate(suite.classes()).getFailureReport().getDetails();
            }
        }
        return List.of();
    }

    /**
     * One rule's own description, for an assertion about what the rule <em>names</em> rather than what it
     * reports.
     *
     * <p>Needed where the interesting property is the fence itself - which type or package a rule is scoped
     * to - and no fixture can demonstrate it, because the subject is the rule's configuration rather than
     * the code under analysis.
     */
    static String ruleDescription(ArchitectureRulesConfiguration configuration, RuleId id) {
        ArchitectureRuleSuite suite = ArchitectureRules.suite(configuration);
        for (ResolvedRule rule : suite.rules()) {
            if (rule.id().equals(id)) {
                return rule.description();
            }
        }
        return "";
    }

    /** The ids of every enabled rule the given configuration does not satisfy. */
    static Set<String> failingRuleIds(ArchitectureRulesConfiguration configuration) {
        ArchitectureRuleSuite suite = ArchitectureRules.suite(configuration);
        Set<String> failing = new LinkedHashSet<>();
        for (ResolvedRule rule : suite.rules()) {
            if (rule.evaluate(suite.classes()).hasViolation()) {
                failing.add(rule.id().value());
            }
        }
        return failing;
    }

    /** The report of one rule, for assertions about violation detail rather than about ids. */
    static ru.ludwigandreas.archrules.report.RuleReport ruleReport(ArchitectureRulesConfiguration configuration,
                                                                   RuleId ruleId) {
        return ru.ludwigandreas.archrules.report.ArchitectureRuleRunner.run(ArchitectureRules.suite(configuration))
                .rules().stream()
                .filter(rule -> rule.id().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No rule " + ruleId + " was resolved"));
    }

    /** The ids of every rule the configuration resolves to, whether it passes or not. */
    static Set<String> resolvedRuleIds(ArchitectureRulesConfiguration configuration) {
        Set<String> ids = new LinkedHashSet<>();
        ArchitectureRules.suite(configuration).rules().forEach(rule -> ids.add(rule.id().value()));
        return ids;
    }
}
