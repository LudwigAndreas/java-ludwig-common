package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.RecordingAuditSink;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * An enumeration's trail goes through the platform's one audit sink, in the reconciliation category,
 * with the kind of event carried as an attribute - the same shape every other event of this module
 * has.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationAuditIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private EnumerationRunner runner;

    @Autowired
    private RecordingAuditSink audit;

    @BeforeEach
    void resetState() {
        fixture.reset();
        partner.lists(SCOPE, item("I-1", "one"));
    }

    @Test
    void aWalkIsAuditedWhenItStartsAndWhenItCompletes() {
        fixture.walk(TASK, SCOPE);

        assertThat(audit.withAction("scope.walk.started")).singleElement()
                .satisfies(EnumerationAuditIntegrationTest::assertScopeEvent);
        assertThat(audit.withAction("scope.walk.completed")).hasSize(1);
    }

    @Test
    void aWalkThatFailsIsAuditedAsAFailure() {
        partner.failsAt(SCOPE, 0);

        fixture.walk(TASK, SCOPE);

        assertThat(audit.withAction("scope.walk.failed")).singleElement().satisfies(event ->
                assertThat(event.outcome().toString()).containsIgnoringCase("fail"));
    }

    @Test
    void aRunIsAuditedAroundTheWalksItMakes() {
        runner.discoverPass(fixture.enumeration(TASK));
        audit.reset();

        runner.walkPass(fixture.enumeration(TASK));

        assertThat(audit.withAction("run.started")).singleElement().satisfies(event ->
                assertThat(event.attributes()).containsEntry("eventCategory", "RUN").containsEntry("subject", "walk"));
        assertThat(audit.withAction("run.finished")).hasSize(1);
        // Every event of the run carries the run's id, so one partner interaction is traceable.
        Object runId = audit.withAction("run.started").get(0).attributes().get("runId");
        assertThat(audit.withAction("scope.walk.started").get(0).attributes()).containsEntry("runId", runId);
    }

    private static void assertScopeEvent(AuditEvent event) {
        assertThat(event.category()).isEqualTo("reconciliation");
        assertThat(event.attributes()).containsEntry("taskName", TASK)
                .containsEntry("subject", SCOPE)
                .containsEntry("eventCategory", "SCOPE");
    }
}
