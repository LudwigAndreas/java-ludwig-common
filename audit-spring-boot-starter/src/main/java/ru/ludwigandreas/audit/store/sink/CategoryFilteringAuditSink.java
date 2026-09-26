package ru.ludwigandreas.audit.store.sink;

import java.util.Set;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;

/**
 * Passes only the categories a deployment named on to a delegate.
 *
 * <p>Exists for {@link OutboxAuditSink}, whose publisher demands an ambient transaction: the
 * observational categories have none, so shipping every category through it would turn an authorization
 * denial into an {@code IllegalTransactionStateException}. Rather than teach the outbox sink about
 * categories - which would put a policy decision inside a transport - the filter is its own decorator,
 * which also makes "ship only the settings and access trail to the SIEM" configurable without a second
 * mechanism.
 *
 * <p>An allow-list rather than a deny-list. A category added to the platform later must not start being
 * shipped to somebody's SIEM because nobody thought to exclude it.
 */
public class CategoryFilteringAuditSink implements AuditSink {

    private final AuditSink delegate;
    private final Set<String> categories;

    /**
     * Wraps {@code delegate}.
     *
     * @param delegate   the sink to protect
     * @param categories the categories to pass on; empty passes nothing, which is the safe reading of
     *                   "a deployment enabled the sink and named no categories"
     */
    public CategoryFilteringAuditSink(AuditSink delegate, Set<String> categories) {
        this.delegate = delegate;
        this.categories = categories == null ? Set.of() : Set.copyOf(categories);
    }

    /** The categories this filter passes. */
    public Set<String> categories() {
        return categories;
    }

    @Override
    public void record(AuditEvent event) {
        if (categories.contains(event.category())) {
            delegate.record(event);
        }
    }
}
