package ru.ludwigandreas.testsupport.local.rules;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;

/**
 * The build-time rules for a service that has a local application. A service enables them by
 * subclassing this under its {@code architecture} test directory:
 *
 * <pre>{@code
 * class LocalRunArchitectureTest extends LocalRunRulesTest {
 *
 *     @Override
 *     protected String basePackage() {
 *         return "ru.ludwigandreas.example.catalog";
 *     }
 *
 *     @Override
 *     protected StubCatalog catalog() {
 *         return new CatalogStubs();
 *     }
 * }
 * }</pre>
 *
 * <p>A service with no local application does not subclass it and owes no stub.
 *
 * <h2>Why this is not a {@code RuleGroup} in {@code architecture-rules}</h2>
 *
 * <p>The tool is the same one - ArchUnit owns structure - but that harness cannot host these rules.
 * It imports with {@code DO_NOT_INCLUDE_TESTS}, and both the stubs and the local application are test
 * classes; and it has no in-repo dependencies, on purpose, so it can name neither {@code StubCatalog}
 * nor {@code LocalRun}. A rule that lives with the thing it protects has precedent here:
 * {@code SqlConfinementTest} in the two modules with a SQL carve-out.
 *
 * <h2>What it cannot see</h2>
 *
 * <p>A rest client that exists only as a configured name, with no annotated interface. The launcher's
 * start-up check covers that one, against the registry.
 */
public abstract class LocalRunRulesTest {

    /**
     * The service's root package. Main and test classes under it are imported.
     *
     * @return a package name
     */
    protected abstract String basePackage();

    /**
     * The service's stub catalog - the same one its local application is given.
     *
     * @return the catalog
     */
    protected abstract StubCatalog catalog();

    @Test
    @DisplayName("every @LudwigRestClient has a partner stub or a pass-through declaration")
    void everyRestClientHasAStubOrPassThrough() {
        LocalRunRules.everyRestClientHasAStubOrPassThrough(catalog()).check(imported());
    }

    @Test
    @DisplayName("anything that uses LocalRun lives in a package named local")
    void localApplicationsLiveInALocalPackage() {
        LocalRunRules.localApplicationsLiveInALocalPackage().check(imported());
    }

    private JavaClasses imported() {
        // No import options: test classes are exactly what these rules are about.
        return new ClassFileImporter().importPackages(basePackage());
    }
}
