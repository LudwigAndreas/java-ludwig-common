package ru.ludwigandreas.archrules;

import java.util.List;

import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;
import ru.ludwigandreas.archrules.rules.DtoImmutabilityRules;
import ru.ludwigandreas.archrules.rules.PersistenceRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape a consuming service sees: annotate, extend, get one test per rule.
 *
 * <p>The nested classes are what a service would write. They override {@code customize} only because
 * the fixture services live in test sources, which the production import options exclude by design.
 */
class JUnitIntegrationTest {

    @Test
    @DisplayName("every enabled rule becomes its own named test")
    void everyRuleBecomesItsOwnTest() throws Throwable {
        List<DynamicTest> tests = new CompliantService().architectureRules().toList();

        assertThat(tests).isNotEmpty();
        assertThat(tests).extracting(DynamicTest::getDisplayName)
                .contains(PersistenceRules.ENTITIES_IN_ENTITY_PACKAGES.value());
        for (DynamicTest test : tests) {
            test.getExecutable().execute();
        }
    }

    @Test
    @DisplayName("a configuration that enables nothing fails instead of passing silently")
    void aSuiteWithoutRulesFails() {
        List<DynamicTest> tests = new NothingEnabled().architectureRules().toList();

        assertThat(tests).singleElement()
                .satisfies(test -> assertThatThrownBy(() -> test.getExecutable().execute())
                        .isInstanceOf(AssertionError.class)
                        .hasMessageContaining("No architecture rule is enabled"));
    }

    @Test
    @DisplayName("a base package that imports nothing fails instead of passing vacuously")
    void anEmptyAnalysisFails() {
        // The fixtures live in test sources, which the production import options exclude - the same
        // situation a mistyped base package produces.
        assertThatThrownBy(() -> new MistypedPackages().architectureRules().toList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No classes were imported");
    }

    @Test
    @DisplayName("a rule downgraded to a warning is skipped with its message, not failed")
    void warningRulesAreAbortedRatherThanFailed() {
        List<DynamicTest> tests = new ToleratedViolation().architectureRules().toList();

        DynamicTest violated = tests.stream()
                .filter(test -> test.getDisplayName()
                        .equals(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> violated.getExecutable().execute())
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("setCode");
    }

    @Test
    @DisplayName("the annotation's toggles reach the resolved suite")
    void annotationTogglesAreApplied() {
        assertThat(Fixtures.resolvedRuleIds(new KafkaFreeService().configuration()))
                .noneSatisfy(id -> assertThat(id).startsWith("kafka."));
    }

    @AnalyzeArchitecture(packages = Fixtures.ROOT + ".good", enable = "domain-isolation")
    static class CompliantService extends ArchitectureRulesTest {

        @Override
        protected void customize(ArchitectureRulesConfiguration.Builder builder) {
            builder.importOptions(ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
                            ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                    // the names a consuming service supplies for the shared base types
                    .conventions(conventions -> conventions
                            .types(TypeRole.BASE_ENTITY,
                                    Fixtures.ROOT + ".good.catalog.repository.entity.BaseEntity")
                            .types(TypeRole.BASE_EXCEPTION,
                                    Fixtures.ROOT + ".good.catalog.support.ApplicationException")
                            .types(TypeRole.EVENT_PUBLISHER,
                                    Fixtures.ROOT + ".good.catalog.service.ProductEventPublisher"))
                    .reporting(report -> report.console(false).json(false));
        }
    }

    @AnalyzeArchitecture(packages = Fixtures.ROOT + ".bad.dtos")
    static class ToleratedViolation extends ArchitectureRulesTest {

        @Override
        protected void customize(ArchitectureRulesConfiguration.Builder builder) {
            builder.importOptions(ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
                            ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                    .warnOn(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value())
                    .reporting(report -> report.console(false).json(false));
        }
    }

    @AnalyzeArchitecture(packages = Fixtures.ROOT + ".good", disable = "*")
    static class NothingEnabled extends ArchitectureRulesTest {

        @Override
        protected void customize(ArchitectureRulesConfiguration.Builder builder) {
            builder.importOptions(ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
                            ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                    .reporting(report -> report.console(false).json(false));
        }
    }

    @AnalyzeArchitecture(packages = Fixtures.ROOT + ".good")
    static class MistypedPackages extends ArchitectureRulesTest {
    }

    @AnalyzeArchitecture(packages = Fixtures.ROOT + ".good", disable = "kafka")
    static class KafkaFreeService extends ArchitectureRulesTest {

        @Override
        public ArchitectureRulesConfiguration configuration() {
            return super.configuration();
        }

        @Override
        protected void customize(ArchitectureRulesConfiguration.Builder builder) {
            builder.importOptions(ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
                            ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                    .reporting(report -> report.console(false).json(false));
        }
    }
}
