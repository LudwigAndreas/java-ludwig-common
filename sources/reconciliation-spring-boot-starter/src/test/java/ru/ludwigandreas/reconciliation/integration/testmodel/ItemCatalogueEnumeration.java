package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.MemberFeed;
import ru.ludwigandreas.reconciliation.api.MemberReconciler;
import ru.ludwigandreas.reconciliation.api.ReconciliationEnumeration;
import ru.ludwigandreas.reconciliation.api.ScopeSource;

import java.util.function.Function;

/**
 * One fixed scope with no local row behind it, no incremental walks, and a reconciler that defines
 * nothing for a member that leaves - the shape of "mirror everything one query returns".
 */
@ReconciliationEnumeration(ItemCatalogueEnumeration.NAME)
public class ItemCatalogueEnumeration implements EnumerationTask<String, String, PartnerItem, String> {

    /** The enumeration's name. */
    public static final String NAME = "item-catalogue";

    /** The one scope this enumeration has, and the collection the stub partner lists for it. */
    public static final String SCOPE = "catalogue";

    private final StubCollectionPartner partner;
    private final ItemReconciler reconciler;

    /**
     * Creates the enumeration.
     *
     * @param partner the stub partner
     * @param items   the member table
     */
    public ItemCatalogueEnumeration(StubCollectionPartner partner, LocalItemRepository items) {
        this.partner = partner;
        this.reconciler = new ItemReconciler(items, LocalItem.KIND_ITEM, false);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ScopeSource<String, String> scopes() {
        return ScopeSource.single(SCOPE, SCOPE);
    }

    @Override
    public MemberFeed<String, PartnerItem> feed() {
        return (scope, cursor, request) -> partner.page(scope, cursor, request);
    }

    @Override
    public KeyCodec<String> scopeKeyCodec() {
        return KeyCodec.ofString();
    }

    @Override
    public Function<PartnerItem, String> memberKey() {
        return PartnerItem::id;
    }

    @Override
    public KeyCodec<String> memberKeyCodec() {
        return KeyCodec.ofString();
    }

    @Override
    public MemberReconciler<String, PartnerItem, String> reconciler() {
        return reconciler;
    }

    /** The reconciler, for a test that asserts what it was asked. */
    public ItemReconciler recorded() {
        return reconciler;
    }
}
