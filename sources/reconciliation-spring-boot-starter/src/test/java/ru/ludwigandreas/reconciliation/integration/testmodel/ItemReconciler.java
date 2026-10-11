package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.reconciliation.api.LeftResult;
import ru.ludwigandreas.reconciliation.api.MemberContext;
import ru.ludwigandreas.reconciliation.api.MemberReconciler;
import ru.ludwigandreas.reconciliation.api.MemberResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The reconciler the test enumerations share: an upsert by external id that restores a removed row,
 * with two titles a test can use to make it misbehave.
 *
 * <p>Records what it was asked, because several scenarios are about a call that must <em>not</em>
 * happen - an unchanged item, a member of another scope - and a row that did not change proves
 * nothing about whether the integration was called.
 */
public class ItemReconciler implements MemberReconciler<String, PartnerItem, String> {

    /** A title the reconciler throws on. */
    public static final String EXPLODE = "EXPLODE";

    /** A title the reconciler declines. */
    public static final String UNWANTED = "UNWANTED";

    /** One call to {@link #apply}. */
    public record Applied(String scope, String id, boolean isNew) {
    }

    /** One call to {@link #left}. */
    public record Left(String scope, String id) {
    }

    private final LocalItemRepository items;
    private final String kind;
    private final boolean handlesRemoval;
    private final List<Applied> applied = Collections.synchronizedList(new ArrayList<>());
    private final List<Left> left = Collections.synchronizedList(new ArrayList<>());

    /**
     * Creates the reconciler.
     *
     * @param items          the member table
     * @param kind           the kind of row this reconciler writes
     * @param handlesRemoval whether a member leaving flags the row as removed, or leaves it alone
     */
    public ItemReconciler(LocalItemRepository items, String kind, boolean handlesRemoval) {
        this.items = items;
        this.kind = kind;
        this.handlesRemoval = handlesRemoval;
    }

    /** Forgets the calls recorded so far. */
    public void reset() {
        applied.clear();
        left.clear();
    }

    /** Every call to {@link #apply} since the last reset. */
    public List<Applied> appliedCalls() {
        return List.copyOf(applied);
    }

    /** Every call to {@link #left} since the last reset. */
    public List<Left> leftCalls() {
        return List.copyOf(left);
    }

    @Override
    public MemberResult apply(String scope, PartnerItem item, MemberContext context) {
        applied.add(new Applied(scope, item.id(), context.isNew()));
        if (EXPLODE.equals(item.title())) {
            throw new IllegalStateException("cannot apply " + item.id());
        }
        if (UNWANTED.equals(item.title())) {
            return MemberResult.skipped("not wanted");
        }
        LocalItem local = items.byExternalId(item.id()).orElseGet(() -> {
            LocalItem created = new LocalItem();
            created.setExternalId(item.id());
            created.setKind(kind);
            return created;
        });
        local.setParent(scope);
        local.setTitle(item.title());
        local.setRemoved(false);
        local.setSyncCount(local.getSyncCount() + 1);
        items.save(local);
        return MemberResult.applied("title -> " + item.title());
    }

    @Override
    public LeftResult left(String scope, String memberKey, MemberContext context) {
        left.add(new Left(scope, memberKey));
        if (!handlesRemoval) {
            return LeftResult.KEPT;
        }
        items.byExternalId(memberKey).ifPresent(local -> {
            local.setRemoved(true);
            items.save(local);
        });
        return LeftResult.HANDLED;
    }
}
