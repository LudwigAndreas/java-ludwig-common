package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ScopedItemsEnumeration;
import ru.ludwigandreas.reconciliation.repository.SyncScopeStateRepository;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scopes are found from local rows, registered once, and queued so that one with no data at all
 * comes before one whose data is merely old.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationDiscoveryIntegrationTest {

    private static final String TASK = ScopedItemsEnumeration.NAME;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private EnumerationRunner runner;

    @Autowired
    private SyncScopeStateRepository states;

    @BeforeEach
    void resetState() {
        fixture.reset();
    }

    @Test
    void everyLocalScopeIsRegisteredDueImmediately() {
        fixture.givenScope("S-1");
        fixture.givenScope("S-2");
        fixture.givenScope("S-3");

        int registered = runner.discoverPass(fixture.enumeration(TASK));

        assertThat(registered).isEqualTo(3);
        assertThat(states.findDue(TASK, Instant.now(), 10))
                .extracting(SyncScopeState::getScopeKey).containsExactly("S-1", "S-2", "S-3");
    }

    @Test
    void aSecondPassRegistersOnlyWhatIsNew() {
        fixture.givenScope("S-1");
        runner.discoverPass(fixture.enumeration(TASK));
        fixture.givenScope("S-2");

        assertThat(runner.discoverPass(fixture.enumeration(TASK))).isEqualTo(1);
        assertThat(runner.discoverPass(fixture.enumeration(TASK))).isZero();
        assertThat(states.countScopes(TASK)).isEqualTo(2);
    }

    @Test
    void discoveryNeverForgetsAScope() {
        fixture.givenScope("S-1");
        runner.discoverPass(fixture.enumeration(TASK));
        fixture.removeScope("S-1");

        runner.discoverPass(fixture.enumeration(TASK));

        // Forgetting is the walk's job: it is the one place that can tell gone from not-on-this-page.
        assertThat(states.findScope(TASK, "S-1")).isPresent();
    }

    @Test
    void aSingleFixedScopeIsDiscoveredWithNoLocalRowBehindIt() {
        runner.discoverPass(fixture.enumeration(ItemCatalogueEnumeration.NAME));

        assertThat(states.findScope(ItemCatalogueEnumeration.NAME, ItemCatalogueEnumeration.SCOPE)).isPresent();
    }

    @Test
    void aScopeThatHasNeverBeenWalkedIsTakenBeforeOneThatIsOnlyOverdue() {
        fixture.givenScope("S-1");
        runner.discoverPass(fixture.enumeration(TASK));
        fixture.walk(TASK, "S-1");
        SyncScopeState walked = fixture.state(TASK, "S-1");
        walked.setNextDueAt(Instant.now().minusSeconds(60));
        states.save(walked);
        fixture.givenScope("S-2");
        runner.discoverPass(fixture.enumeration(TASK));

        List<SyncScopeState> next = states.findDue(TASK, Instant.now(), 1);

        assertThat(next).extracting(SyncScopeState::getScopeKey).containsExactly("S-2");
    }
}
