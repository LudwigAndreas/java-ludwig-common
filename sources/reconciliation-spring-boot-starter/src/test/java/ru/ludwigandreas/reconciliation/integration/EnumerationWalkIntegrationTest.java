package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.engine.enumeration.SweepService;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemReconciler;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * A single fixed scope walked from nothing: the first fetch the key-addressed half of this module
 * cannot do, and the unchanged-item short-circuit that replaces the staging inbox for it.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationWalkIntegrationTest {

    private static final String TASK = ItemCatalogueEnumeration.NAME;
    private static final String SCOPE = ItemCatalogueEnumeration.SCOPE;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private ItemCatalogueEnumeration catalogue;

    @Autowired
    private SyncInboxRecordRepository inbox;

    @Autowired
    private EnumerationRunner runner;

    @BeforeEach
    void resetState() {
        fixture.reset();
    }

    @Test
    void theFirstWalkCreatesEveryItemThePartnerLists() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"));

        WalkReport report = fixture.walk(TASK, SCOPE);

        assertThat(report.status()).isEqualTo(WalkReport.Status.COMPLETED);
        assertThat(report.kind()).isEqualTo(ScopeWalkKind.FULL);
        assertThat(report.members().applied()).isEqualTo(3);
        assertThat(catalogue.recorded().appliedCalls())
                .extracting(ItemReconciler.Applied::id, ItemReconciler.Applied::isNew)
                .containsExactly(tuple("I-1", true),
                        tuple("I-2", true),
                        tuple("I-3", true));
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2", "I-3");
        assertThat(fixture.local("I-2").getTitle()).isEqualTo("two");
    }

    @Test
    void anUnchangedItemIsSeenWithoutCallingTheIntegration() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));
        fixture.walk(TASK, SCOPE);
        catalogue.recorded().reset();

        WalkReport second = fixture.fullWalk(TASK, SCOPE);

        assertThat(catalogue.recorded().appliedCalls()).isEmpty();
        assertThat(second.members().unchanged()).isEqualTo(2);
        assertThat(fixture.local("I-1").getSyncCount()).isEqualTo(1);
        assertThat(fixture.member(TASK, SCOPE, "I-1").getSeenGeneration()).isEqualTo(2);
    }

    @Test
    void aChangedItemIsAppliedAsAnExistingMember() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));
        fixture.walk(TASK, SCOPE);
        String hashBefore = fixture.member(TASK, SCOPE, "I-2").getPayloadHash();
        catalogue.recorded().reset();
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two, revised"));

        fixture.fullWalk(TASK, SCOPE);

        assertThat(catalogue.recorded().appliedCalls())
                .containsExactly(new ItemReconciler.Applied(SCOPE, "I-2", false));
        assertThat(fixture.local("I-2").getTitle()).isEqualTo("two, revised");
        assertThat(fixture.member(TASK, SCOPE, "I-2").getPayloadHash()).isNotEqualTo(hashBefore);
    }

    @Test
    void anItemTheIntegrationDeclinesIsNotAMember() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", ItemReconciler.UNWANTED));

        WalkReport report = fixture.walk(TASK, SCOPE);

        assertThat(report.members().skipped()).isEqualTo(1);
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1");
        assertThat(catalogue.recorded().leftCalls()).isEmpty();
    }

    @Test
    void aMemberTheIntegrationStartsDecliningIsReportedAsHavingLeft() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", ItemReconciler.UNWANTED));

        fixture.fullWalk(TASK, SCOPE);

        assertThat(catalogue.recorded().leftCalls()).containsExactly(new ItemReconciler.Left(SCOPE, "I-2"));
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1");
    }

    @Test
    void aMemberAbsentFromAFullWalkIsReportedAndForgottenButNothingOfTheIntegrationsIsTouched() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"), item("I-3", "three"));
        fixture.walk(TASK, SCOPE);
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));

        WalkReport report = fixture.fullWalk(TASK, SCOPE);

        assertThat(report.sweep().verdict()).isEqualTo(SweepService.Verdict.SWEPT);
        assertThat(catalogue.recorded().leftCalls()).containsExactly(new ItemReconciler.Left(SCOPE, "I-3"));
        assertThat(fixture.memberKeys(TASK, SCOPE)).containsExactly("I-1", "I-2");
        // This enumeration defines no removal behaviour, so the engine's own row is all that goes.
        assertThat(fixture.local("I-3").isRemoved()).isFalse();
    }

    @Test
    void aScopeThatIsNotDueIsNotFetched() {
        partner.lists(SCOPE, item("I-1", "one"));
        fixture.walk(TASK, SCOPE);
        partner.forgetCalls();

        assertThat(runner.walkDue(fixture.enumeration(TASK))).isEmpty();
        assertThat(partner.calls()).isEmpty();
    }

    @Test
    void anEnumerationWritesNothingToTheStagingInbox() {
        partner.lists(SCOPE, item("I-1", "one"), item("I-2", "two"));

        fixture.walk(TASK, SCOPE);

        assertThat(inbox.findAll()).noneMatch(record -> TASK.equals(record.getTaskName()));
    }
}
