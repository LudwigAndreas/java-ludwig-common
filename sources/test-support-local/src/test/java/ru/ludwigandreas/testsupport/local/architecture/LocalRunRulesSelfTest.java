package ru.ludwigandreas.testsupport.local.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.testsupport.local.architecture.violations.misplaced.MisplacedLauncher;
import ru.ludwigandreas.testsupport.local.architecture.violations.orphan.OrphanApi;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.integration.sample.local.LocalSampleApplication;
import ru.ludwigandreas.testsupport.local.rules.LocalRunRules;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;

/**
 * Evaluates the rules against classes written to break them.
 *
 * <p>The sample passing ({@link SampleLocalRunRulesTest}) shows the rules accept a correct service.
 * It does not show they reject anything, and a rule that accepts everything looks identical from
 * there.
 */
class LocalRunRulesSelfTest {

    private static final String VIOLATIONS = "ru.ludwigandreas.testsupport.local.architecture.violations";

    private final JavaClasses violations = new ClassFileImporter().importPackages(VIOLATIONS);

    @Test
    @DisplayName("a client with no stub fails the rule, naming the interface and the client")
    void orphanClientIsReported() {
        EvaluationResult result =
                LocalRunRules.everyRestClientHasAStubOrPassThrough(new SampleStubs()).evaluate(violations);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString())
                .contains(OrphanApi.class.getName())
                .contains("'orphan'");
    }

    @Test
    @DisplayName("declaring the client pass-through satisfies the rule")
    void passThroughSatisfiesTheRule() {
        StubCatalog withPassThrough = new StubCatalog() {
            @Override
            public List<PartnerStub> stubs() {
                return List.of();
            }

            @Override
            public Set<String> passThrough() {
                return Set.of("orphan");
            }
        };

        assertThat(LocalRunRules.everyRestClientHasAStubOrPassThrough(withPassThrough)
                .evaluate(violations).hasViolation()).isFalse();
    }

    @Test
    @DisplayName("a launcher outside a package named local fails the rule, naming the class")
    void misplacedLauncherIsReported() {
        EvaluationResult result = LocalRunRules.localApplicationsLiveInALocalPackage().evaluate(violations);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(MisplacedLauncher.class.getName());
    }

    @Test
    @DisplayName("the sample's local application, in a package named local, is accepted")
    void wellPlacedLauncherIsAccepted() {
        JavaClasses wellPlaced = new ClassFileImporter().importClasses(LocalSampleApplication.class);

        assertThat(LocalRunRules.localApplicationsLiveInALocalPackage()
                .evaluate(wellPlaced).hasViolation()).isFalse();
    }
}
