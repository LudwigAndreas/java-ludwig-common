package ru.ludwigandreas.archrules;

import java.util.LinkedHashSet;
import java.util.Set;

import com.tngtech.archunit.core.importer.ImportOption;

import ru.ludwigandreas.archrules.TypeRole;

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
