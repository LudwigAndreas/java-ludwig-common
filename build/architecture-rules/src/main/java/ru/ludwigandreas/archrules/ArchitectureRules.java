package ru.ludwigandreas.archrules;

import java.util.Objects;

import com.tngtech.archunit.core.domain.JavaClasses;

import ru.ludwigandreas.archrules.internal.ImportedClasses;
import ru.ludwigandreas.archrules.report.ArchitectureReport;
import ru.ludwigandreas.archrules.report.ArchitectureReports;
import ru.ludwigandreas.archrules.report.ArchitectureRuleRunner;
import ru.ludwigandreas.archrules.report.RuleReport;

/**
 * Entry point of the library.
 *
 * <p>Inside a JUnit 5 build the usual way in is the annotation:
 *
 * <pre>{@code
 * @AnalyzeArchitecture(packagesOf = OrdersApplication.class, disable = "kafka")
 * class ArchitectureTest extends ArchitectureRulesTest {
 * }
 * }</pre>
 *
 * <p>Everything the annotation does is available programmatically as well, for a Gradle/Maven plugin,
 * a custom runner, or a test that wants to assert on the resolved rules themselves:
 *
 * <pre>{@code
 * ArchitectureRules.check(ArchitectureRulesConfiguration.builder()
 *         .basePackages("com.acme.orders")
 *         .disable("kafka")
 *         .build());
 * }</pre>
 */
public final class ArchitectureRules {

    private ArchitectureRules() {
    }

    /** Imports the configured packages (cached) and resolves the enabled rules. */
    public static ArchitectureRuleSuite suite(ArchitectureRulesConfiguration configuration) {
        return ArchitectureRuleSuite.of(Objects.requireNonNull(configuration, "configuration"));
    }

    /** Resolves the enabled rules against an already imported class set. */
    public static ArchitectureRuleSuite suite(ArchitectureRulesConfiguration configuration, JavaClasses classes) {
        return ArchitectureRuleSuite.of(Objects.requireNonNull(configuration, "configuration"),
                Objects.requireNonNull(classes, "classes"));
    }

    /** Imports, resolves and checks in one call, failing with every violation found. */
    public static void check(ArchitectureRulesConfiguration configuration) {
        suite(configuration).check();
    }

    /**
     * Evaluates every rule and emits the configured reports, returning the report rather than
     * throwing. The entry point for a Gradle task, a CI step or anything else that wants the run
     * without a JUnit runner around it.
     */
    public static ArchitectureReport run(ArchitectureRulesConfiguration configuration) {
        ArchitectureReport report = ArchitectureRuleRunner.run(suite(configuration));
        ArchitectureReports.write(report, configuration.reporting());
        return report;
    }

    /**
     * {@link #run(ArchitectureRulesConfiguration)}, failing afterwards if any rule violated at
     * {@link RuleSeverity#ERROR}. Warnings are reported and do not fail.
     */
    public static ArchitectureReport checkAndReport(ArchitectureRulesConfiguration configuration) {
        ArchitectureReport report = run(configuration);
        if (report.rules().isEmpty()) {
            throw new AssertionError("No architecture rule is enabled for " + configuration
                    + ". Every rule was switched off, so this check guarantees nothing.");
        }
        if (report.hasFailures()) {
            StringBuilder message = new StringBuilder(report.failures().size()
                    + " of " + report.summary().rules() + " architecture rules failed:");
            for (RuleReport failure : report.failures()) {
                message.append(System.lineSeparator()).append(System.lineSeparator())
                        .append(failure.failureMessage());
            }
            throw new AssertionError(message.toString());
        }
        return report;
    }

    /** The imported classes for this configuration, sharing the suite's import cache. */
    public static JavaClasses importClasses(ArchitectureRulesConfiguration configuration) {
        return ImportedClasses.of(Objects.requireNonNull(configuration, "configuration"));
    }

    /** Drops the import cache; only needed by tests that re-import with different options. */
    public static void clearImportCache() {
        ImportedClasses.clearCache();
    }
}
