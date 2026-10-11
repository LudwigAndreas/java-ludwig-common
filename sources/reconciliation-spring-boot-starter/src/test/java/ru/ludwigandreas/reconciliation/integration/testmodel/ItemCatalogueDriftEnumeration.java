package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.MemberFeed;
import ru.ludwigandreas.reconciliation.api.MemberReconciler;
import ru.ludwigandreas.reconciliation.api.ReconciliationEnumeration;
import ru.ludwigandreas.reconciliation.api.ScopeSource;

import java.util.function.Function;

/**
 * A single-scope enumeration configured to require two consecutive misses before a member is gone,
 * as an integration over offset paging would be.
 */
@ReconciliationEnumeration(ItemCatalogueDriftEnumeration.NAME)
public class ItemCatalogueDriftEnumeration implements EnumerationTask<String, String, PartnerItem, String> {

    /** The enumeration's name. */
    public static final String NAME = "item-catalogue-drift";

    /** The one scope this enumeration has, and the collection the stub partner lists for it. */
    public static final String SCOPE = "drift";

    private final StubCollectionPartner partner;
    private final ItemReconciler reconciler;

    /**
     * Creates the enumeration.
     *
     * @param partner the stub partner
     * @param items   the member table
     */
    public ItemCatalogueDriftEnumeration(StubCollectionPartner partner, LocalItemRepository items) {
        this.partner = partner;
        this.reconciler = new ItemReconciler(items, LocalItem.KIND_ITEM, true);
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
