package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
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
 * A scope per local row, with incremental walks between full ones - the shape of "one query per
 * stored object".
 */
@ReconciliationEnumeration(ScopedItemsEnumeration.NAME)
public class ScopedItemsEnumeration implements EnumerationTask<String, String, PartnerItem, String> {

    /** The enumeration's name. */
    public static final String NAME = "scoped-items";

    private final StubCollectionPartner partner;
    private final LocalScopeRepository scopes;
    private final ItemReconciler reconciler;

    /**
     * Creates the enumeration.
     *
     * @param partner the stub partner
     * @param scopes  the scope table
     * @param items   the member table
     */
    public ScopedItemsEnumeration(StubCollectionPartner partner,
                                  LocalScopeRepository scopes,
                                  LocalItemRepository items) {
        this.partner = partner;
        this.scopes = scopes;
        this.reconciler = new ItemReconciler(items, LocalItem.KIND_ITEM, true);
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
                return scopes.findAll().stream()
                        .map(LocalScope::getCode)
                        .filter(code -> after == null || code.compareTo(after) > 0)
                        .sorted(Comparator.naturalOrder())
                        .limit(limit)
                        .toList();
            }

            @Override
            public Optional<String> byKey(String key) {
                return scopes.findAll().stream().map(LocalScope::getCode).filter(key::equals).findFirst();
            }

            @Override
            public String keyOf(String scope) {
                return scope;
            }
        };
    }

    @Override
    public MemberFeed<String, PartnerItem> feed() {
        return new MemberFeed<>() {
            @Override
            public ru.ludwigandreas.reconciliation.api.PageResult<PartnerItem> fetchPage(
                    String scope, String cursor, ru.ludwigandreas.reconciliation.api.FeedRequest request) {
                return partner.page(scope, cursor, request);
            }

            @Override
            public boolean supportsChangedSince() {
                return true;
            }
        };
    }

    @Override
    public ExternalStamp stampOf(PartnerItem item) {
        return item.changedAt() == null ? ExternalStamp.none() : ExternalStamp.ofTimestamp(item.changedAt());
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
