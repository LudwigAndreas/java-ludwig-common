package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRuntime;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * At most one instance walks an enumeration's scopes at a time, and the passes are scheduled on the
 * module's own scheduler under the task's lease.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationRunLockIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private EnumerationRunner runner;

    @Autowired
    private EnumerationRuntime runtime;

    @Autowired
    private RunLock runLock;

    @BeforeEach
    void resetState() {
        fixture.reset();
        partner.lists(SCOPE, item("I-1", "one"));
    }

    @Test
    void aRunHeldByAnotherInstanceMakesNoCallToThePartner() {
        runner.discoverPass(fixture.enumeration(TASK));
        Optional<RunLockHandle> heldElsewhere =
                runLock.tryAcquire("reconciliation:" + TASK + ":walk", Duration.ofMinutes(5));
        assertThat(heldElsewhere).isPresent();

        try (RunLockHandle ignored = heldElsewhere.get()) {
            assertThat(runner.walkPass(fixture.enumeration(TASK))).isZero();
            assertThat(runner.walkScope(fixture.enumeration(TASK), SCOPE, true)).isEmpty();
            assertThat(partner.calls()).isEmpty();
        }
    }

    @Test
    void onceTheLeaseIsReleasedTheRunGoesAhead() {
        runner.discoverPass(fixture.enumeration(TASK));
        runLock.tryAcquire("reconciliation:" + TASK + ":walk", Duration.ofMinutes(5)).orElseThrow().close();

        assertThat(runner.walkPass(fixture.enumeration(TASK))).isEqualTo(1);
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1");
    }

    @Test
    void everyEnabledEnumerationHasADiscoverAndAWalkPass() {
        assertThat(runtime.passNames()).contains(TASK + ":discover", TASK + ":walk");
    }

    @Test
    void aScheduledPassRunOnDemandDiscoversAndWalks() {
        assertThat(runtime.runNow(TASK + ":discover")).isTrue();
        assertThat(runtime.runNow(TASK + ":walk")).isTrue();

        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1");
    }
}
