package ru.ludwigandreas.archrules.junit;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.platform.commons.support.AnnotationSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.junit.jupiter.api.Assumptions;

import ru.ludwigandreas.archrules.ArchitectureRuleSuite;
import ru.ludwigandreas.archrules.ArchitectureRules;
import ru.ludwigandreas.archrules.ArchitectureRulesConfiguration;
import ru.ludwigandreas.archrules.config.ArchitectureRulesProperties;
import ru.ludwigandreas.archrules.report.ArchitectureReport;
import ru.ludwigandreas.archrules.report.ArchitectureReports;
import ru.ludwigandreas.archrules.report.ArchitectureRuleRunner;
import ru.ludwigandreas.archrules.report.RuleReport;

/**
 * Base class that turns the configured rules into one JUnit test each.
 *
 * <pre>{@code
 * @AnalyzeArchitecture(packagesOf = OrdersApplication.class)
 * class ArchitectureTest extends ArchitectureRulesTest {
 * }
 * }</pre>
 *
 * <p>One dynamic test per rule rather than one test for everything: the build report then names the
 * rule that broke ({@code persistence.entities-reside-in-entity-packages}), a newly failing rule
 * shows up as a newly failing test instead of a longer message, and the id in the report is exactly
 * the string to put in {@code disable} if the team decides the rule does not apply.
 *
 * <p>The rules are resolved from the {@link AnalyzeArchitecture} annotation, the properties file and
 * {@link #customize}, in that order. Overriding {@link #configuration()} replaces the lot for a test
 * that wants to build its configuration entirely in code.
 */
public abstract class ArchitectureRulesTest {

    /**
     * Runs every enabled rule as its own test, and emits the console and JSON reports.
     *
     * <p>The rules are evaluated up front rather than inside each test: both reports describe the
     * whole run and have to be written even when rules failed, which is not possible if each test
     * evaluates its own rule as JUnit gets to it. The dynamic tests then only replay the outcome the
     * runner already recorded.
     */
    @TestFactory
    @DisplayName("Architecture rules")
    public Stream<DynamicTest> architectureRules() {
        ArchitectureRulesConfiguration configuration = configuration();
        ArchitectureRuleSuite suite = ArchitectureRules.suite(configuration);
        if (suite.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest("no architecture rules enabled", () -> {
                throw new AssertionError("No architecture rule is enabled for " + suite.configuration()
                        + ". Every rule was switched off, or the analysed packages contain no classes -"
                        + " either way this test would silently guarantee nothing.");
            }));
        }
        ArchitectureReport report = ArchitectureRuleRunner.run(suite);
        ArchitectureReports.write(report, configuration.reporting());
        return report.rules().stream()
                .map(rule -> DynamicTest.dynamicTest(rule.displayName(), () -> verify(rule)));
    }

    /**
     * Turns a recorded outcome into a test result: an ERROR violation fails, a WARNING violation is
     * aborted rather than failed, so it shows up as skipped-with-a-reason instead of going unnoticed
     * or breaking the build.
     *
     * <p>The failure message repeats the rule id, because Surefire's XML names a dynamic test by its
     * index ({@code architectureRules()[13]}) rather than by its display name. (Setting
     * {@code usePhrasedTestCaseMethodName} in the Surefire reporter configuration puts the display
     * name in the XML as well - see this module's README.)
     */
    private static void verify(RuleReport rule) {
        if (rule.isFailure()) {
            throw new AssertionError(rule.failureMessage());
        }
        if (rule.isWarning()) {
            Assumptions.abort(rule.failureMessage());
        }
    }

    /**
     * The configuration to check. Built from the annotation, the properties file and
     * {@link #customize}; override to take full control.
     */
    protected ArchitectureRulesConfiguration configuration() {
        Optional<AnalyzeArchitecture> annotation =
                AnnotationSupport.findAnnotation(getClass(), AnalyzeArchitecture.class);
        ArchitectureRulesConfiguration.Builder builder = ArchitectureRulesConfiguration.builder();

        String propertiesResource = annotation
                .map(AnalyzeArchitecture::properties)
                .orElse(ArchitectureRulesProperties.DEFAULT_RESOURCE);
        if (!propertiesResource.isEmpty()) {
            ArchitectureRulesProperties.applyTo(ArchitectureRulesProperties.fromClasspath(propertiesResource), builder);
        }
        annotation.ifPresent(declared -> apply(declared, builder));
        customize(builder);
        return builder.build();
    }

    /**
     * Hook for the settings an annotation cannot express - a custom rule set, a module
     * customization, a convention of the service's own.
     *
     * <pre>{@code
     * protected void customize(ArchitectureRulesConfiguration.Builder builder) {
     *     builder.conventions(conventions -> conventions.addPackages(PackageRole.ENTITY, "..jpa.."))
     *            .addRuleSet(new AcmeNamingRules());
     * }
     * }</pre>
     */
    protected void customize(ArchitectureRulesConfiguration.Builder builder) {
        // no customization by default
    }

    private static void apply(AnalyzeArchitecture annotation, ArchitectureRulesConfiguration.Builder builder) {
        builder.basePackages(annotation.packages())
                .basePackageOf(annotation.packagesOf())
                .modules(annotation.modules())
                .enable(annotation.enable())
                .disable(annotation.disable());
        annotation.allowEmptyShould().ifSpecified(builder::allowEmptyShould);
        annotation.freeze().ifSpecified(builder::freeze);
    }
}
