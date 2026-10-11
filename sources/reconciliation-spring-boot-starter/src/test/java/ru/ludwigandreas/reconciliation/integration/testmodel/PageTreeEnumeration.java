package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.MemberFeed;
import ru.ludwigandreas.reconciliation.api.MemberReconciler;
import ru.ludwigandreas.reconciliation.api.ReconciliationEnumeration;
import ru.ludwigandreas.reconciliation.api.ScopeSource;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A self-similar tree: every page this enumeration saves is itself a scope of the same enumeration.
 *
 * <p>Nothing in the engine knows this is a tree. The scope source simply reads the rows the
 * reconciler writes, so each discover pass finds the pages the last walk pass created.
 */
@ReconciliationEnumeration(PageTreeEnumeration.NAME)
public class PageTreeEnumeration implements EnumerationTask<String, String, PartnerItem, String> {

    /** The enumeration's name. */
    public static final String NAME = "page-tree";

    private final StubCollectionPartner partner;
    private final LocalItemRepository items;
    private final ItemReconciler reconciler;

    /**
     * Creates the enumeration.
     *
     * @param partner the stub partner
     * @param items   the page table
     */
    public PageTreeEnumeration(StubCollectionPartner partner, LocalItemRepository items) {
        this.partner = partner;
        this.items = items;
        this.reconciler = new ItemReconciler(items, LocalItem.KIND_PAGE, true);
    }

    /**
     * The collection the stub partner lists a page's children under.
     *
     * @param pageId the page
     * @return the collection name
     */
    public static String childrenOf(String pageId) {
        return "children:" + pageId;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ScopeSource<String, String> scopes() {
        return new ScopeSource<>() {
            @Override
            public List<String> page(String after, int limit) {
                return pages().filter(id -> after == null || id.compareTo(after) > 0)
                        .sorted(Comparator.naturalOrder())
                        .limit(limit)
                        .toList();
            }

            @Override
            public Optional<String> byKey(String key) {
                return pages().filter(key::equals).findFirst();
            }

            @Override
            public String keyOf(String scope) {
                return scope;
            }
        };
    }

    private java.util.stream.Stream<String> pages() {
        return items.findAll().stream()
                .filter(item -> LocalItem.KIND_PAGE.equals(item.getKind()) && !item.isRemoved())
                .map(LocalItem::getExternalId);
    }

    @Override
    public MemberFeed<String, PartnerItem> feed() {
        return (scope, cursor, request) -> partner.page(childrenOf(scope), cursor, request);
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
