package ru.ludwigandreas.reconciliation.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalItem;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalItemRepository;
import ru.ludwigandreas.reconciliation.integration.testmodel.PageTreeEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;


import static org.assertj.core.api.Assertions.assertThat;
import static ru.ludwigandreas.reconciliation.integration.EnumerationFixture.item;

/**
 * A self-similar tree, walked to a fixed point by an engine that has no notion of a tree: each pass
 * discovers the pages the last one saved.
 */
@SpringBootTest(classes = ReconciliationTestApplication.class)
@Import(PostgresContainerConfiguration.class)
class EnumerationTreeIntegrationTest {

    private static final String TASK = PageTreeEnumeration.NAME;
    private static final int MORE_PASSES_THAN_THE_TREE_IS_DEEP = 6;

    @Autowired
    private EnumerationFixture fixture;

    @Autowired
    private StubCollectionPartner partner;

    @Autowired
    private EnumerationRunner runner;

    @Autowired
    private LocalItemRepository items;

    @BeforeEach
    void resetState() {
        fixture.reset();
    }

    @Test
    void theWholeTreeUnderARootIsMirroredOneLevelPerPass() {
        fixture.givenPage("root");
        partner.lists(PageTreeEnumeration.childrenOf("root"), item("a", "A"), item("b", "B"));
        partner.lists(PageTreeEnumeration.childrenOf("a"), item("a1", "A1"), item("a2", "A2"), item("a3", "A3"));
        partner.lists(PageTreeEnumeration.childrenOf("a2"), item("a2x", "A2X"));

        int passes = 0;
        int walked;
        do {
            runner.discoverPass(fixture.enumeration(TASK));
            walked = runner.walkPass(fixture.enumeration(TASK));
            passes++;
        } while (walked > 0 && passes < MORE_PASSES_THAN_THE_TREE_IS_DEEP);

        assertThat(items.findAll()).extracting(LocalItem::getExternalId)
                .containsExactlyInAnyOrder("root", "a", "b", "a1", "a2", "a3", "a2x");
        assertThat(fixture.local("a2x").getParent()).isEqualTo("a2");
        assertThat(fixture.local("a3").getParent()).isEqualTo("a");
        // A leaf is a scope too, with nothing under it: it was walked, and found empty.
        assertThat(fixture.state(TASK, "a2x").getGeneration()).isEqualTo(1);
        assertThat(walked).isZero();
    }

    @Test
    void aChildThatDisappearsStopsBeingAScopeOnceItsOwnRowIsFlagged() {
        fixture.givenPage("root");
        partner.lists(PageTreeEnumeration.childrenOf("root"), item("a", "A"), item("b", "B"), item("c", "C"));
        runner.discoverPass(fixture.enumeration(TASK));
        runner.walkPass(fixture.enumeration(TASK));
        runner.discoverPass(fixture.enumeration(TASK));
        runner.walkPass(fixture.enumeration(TASK));
        partner.lists(PageTreeEnumeration.childrenOf("root"), item("a", "A"), item("b", "B"));

        fixture.fullWalk(TASK, "root");
        fixture.walk(TASK, "c");

        assertThat(fixture.local("c").isRemoved()).isTrue();
        // The integration flagged the page, so its scope source no longer returns it, and the next
        // walk of that scope forgets it - a cascade the engine never had to be told about.
        assertThat(fixture.memberKeys(TASK, "root")).containsExactly("a", "b");
        assertThat(items.byExternalId("c")).isPresent();
    }
}
