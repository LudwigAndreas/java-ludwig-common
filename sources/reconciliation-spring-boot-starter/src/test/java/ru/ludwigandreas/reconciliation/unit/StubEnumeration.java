package ru.ludwigandreas.reconciliation.unit;

import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.FeedRequest;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.MemberFeed;
import ru.ludwigandreas.reconciliation.api.MemberReconciler;
import ru.ludwigandreas.reconciliation.api.MemberResult;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.api.ReconciliationEnumeration;
import ru.ludwigandreas.reconciliation.api.ScopeSource;

import java.util.function.Function;

/**
 * The smallest enumeration there is, for tests that need a bean and not a behaviour.
 *
 * <p>Annotated with a fixed name while {@link #name()} is whatever the test says, so that the
 * validator's "annotation and name disagree" case can be produced.
 */
@ReconciliationEnumeration(StubEnumeration.ANNOTATED_NAME)
class StubEnumeration implements EnumerationTask<String, String, String, String> {

    static final String ANNOTATED_NAME = "items";

    private final String name;
    private final boolean changedSince;

    StubEnumeration(String name, boolean changedSince) {
        this.name = name;
        this.changedSince = changedSince;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ScopeSource<String, String> scopes() {
        return ScopeSource.single("all", "all");
    }

    @Override
    public KeyCodec<String> scopeKeyCodec() {
        return KeyCodec.ofString();
    }

    @Override
    public MemberFeed<String, String> feed() {
        return new MemberFeed<>() {
            @Override
            public PageResult<String> fetchPage(String scope, String cursor, FeedRequest request) {
                return PageResult.empty();
            }

            @Override
            public boolean supportsChangedSince() {
                return changedSince;
            }
        };
    }

    @Override
    public Function<String, String> memberKey() {
        return Function.identity();
    }

    @Override
    public KeyCodec<String> memberKeyCodec() {
        return KeyCodec.ofString();
    }

    @Override
    public MemberReconciler<String, String, String> reconciler() {
        return (scope, item, context) -> MemberResult.unchanged();
    }
}
