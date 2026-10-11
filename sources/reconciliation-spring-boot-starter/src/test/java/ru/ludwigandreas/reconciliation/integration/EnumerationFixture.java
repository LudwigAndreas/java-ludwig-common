package ru.ludwigandreas.reconciliation.integration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRegistry;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.engine.enumeration.RegisteredEnumeration;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.SyncScopeMember;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueDriftEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.ItemCatalogueEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalItem;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalItemRepository;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalScope;
import ru.ludwigandreas.reconciliation.integration.testmodel.LocalScopeRepository;
import ru.ludwigandreas.reconciliation.integration.testmodel.PageTreeEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.PartnerItem;
import ru.ludwigandreas.reconciliation.integration.testmodel.RecordingAuditSink;
import ru.ludwigandreas.reconciliation.integration.testmodel.ScopedItemsEnumeration;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubCollectionPartner;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;
import ru.ludwigandreas.reconciliation.repository.SyncScopeStateRepository;

import java.time.Instant;
import java.util.List;

/**
 * What every enumeration integration test needs: a clean slate, and short names for the things the
 * scenarios are written in.
 *
 * <p>A bean rather than a base class, so that each test states its own collaborators and shares the
 * one application context every integration test in this module runs in.
 */
@Component
public class EnumerationFixture {

    private final EnumerationRegistry registry;
    private final EnumerationRunner runner;
    private final StubCollectionPartner partner;
    private final RecordingAuditSink audit;
    private final LocalItemRepository items;
    private final LocalScopeRepository scopes;
    private final SyncScopeStateRepository states;
    private final SyncScopeMemberRepository members;
    private final TransactionTemplate transactions;
    private final ItemCatalogueEnumeration catalogue;
    private final ItemCatalogueDriftEnumeration drift;
    private final ScopedItemsEnumeration scoped;
    private final PageTreeEnumeration tree;

    /** Creates the fixture from the context's beans. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public EnumerationFixture(EnumerationRegistry registry,
                              EnumerationRunner runner,
                              StubCollectionPartner partner,
                              RecordingAuditSink audit,
                              LocalItemRepository items,
                              LocalScopeRepository scopes,
                              SyncScopeStateRepository states,
                              SyncScopeMemberRepository members,
                              TransactionTemplate transactions,
                              ItemCatalogueEnumeration catalogue,
                              ItemCatalogueDriftEnumeration drift,
                              ScopedItemsEnumeration scoped,
                              PageTreeEnumeration tree) {
        this.registry = registry;
        this.runner = runner;
        this.partner = partner;
        this.audit = audit;
        this.items = items;
        this.scopes = scopes;
        this.states = states;
        this.members = members;
        this.transactions = transactions;
        this.catalogue = catalogue;
        this.drift = drift;
        this.scoped = scoped;
        this.tree = tree;
    }

    /** Empties every table an enumeration touches and forgets what the stubs recorded. */
    public void reset() {
        partner.reset();
        audit.reset();
        catalogue.recorded().reset();
        drift.recorded().reset();
        scoped.recorded().reset();
        tree.recorded().reset();
        transactions.executeWithoutResult(status -> {
            members.deleteAllInBatch();
            states.deleteAllInBatch();
            items.deleteAllInBatch();
            scopes.deleteAllInBatch();
        });
    }

    /** An item with no change timestamp. */
    public static PartnerItem item(String id, String title) {
        return new PartnerItem(id, title, null);
    }

    /** An item changed at an instant. */
    public static PartnerItem item(String id, String title, Instant changedAt) {
        return new PartnerItem(id, title, changedAt);
    }

    /** The registered enumeration with a name. */
    public RegisteredEnumeration<?, ?, ?, ?> enumeration(String name) {
        return registry.find(name).orElseThrow();
    }

    /** Walks a scope now, letting the planner choose the kind. */
    public WalkReport walk(String name, String scope) {
        return runner.walkScope(enumeration(name), scope, false).orElseThrow();
    }

    /** Walks a scope now, in full. */
    public WalkReport fullWalk(String name, String scope) {
        return runner.walkScope(enumeration(name), scope, true).orElseThrow();
    }

    /** Saves a local scope row. */
    public void givenScope(String code) {
        LocalScope scope = new LocalScope();
        scope.setCode(code);
        scopes.save(scope);
    }

    /** Deletes a local scope row. */
    public void removeScope(String code) {
        scopes.findAll().stream().filter(scope -> scope.getCode().equals(code)).forEach(scopes::delete);
    }

    /** Saves a local page, the root of a tree. */
    public void givenPage(String id) {
        LocalItem page = new LocalItem();
        page.setExternalId(id);
        page.setKind(LocalItem.KIND_PAGE);
        items.save(page);
    }

    /** The local row for a partner id. */
    public LocalItem local(String id) {
        return items.byExternalId(id).orElseThrow();
    }

    /** The member keys recorded for a scope, sorted. */
    public List<String> memberKeys(String name, String scope) {
        return members.findAllOfScope(name, scope).stream().map(SyncScopeMember::getMemberKey).sorted().toList();
    }

    /** One membership row. */
    public SyncScopeMember member(String name, String scope, String key) {
        return members.findMember(name, scope, key).orElseThrow();
    }

    /** A scope's state row. */
    public SyncScopeState state(String name, String scope) {
        return states.findScope(name, scope).orElseThrow();
    }
}
