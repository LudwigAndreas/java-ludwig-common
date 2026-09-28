package ru.ludwigandreas.reconciliation.integration.testmodel;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.reconciliation.api.DemandProvider;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.PageRequest;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.api.Reconciler;
import ru.ludwigandreas.reconciliation.api.ReconciliationTask;
import ru.ludwigandreas.reconciliation.api.SyncTask;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A paged task, for the cursor-checkpointing and resume assertions.
 *
 * <p>Pages the stub partner's whole catalogue two records at a time, and can be told to throw on a
 * given page so that a sweep can be interrupted half way through.
 */
@ReconciliationTask("partner-catalogue")
public class PartnerCatalogueTask implements SyncTask<LocalOrder, String, BillingRecord> {

    /** Small on purpose: several pages out of a handful of records makes the walk observable. */
    private static final int PAGE_SIZE = 2;

    private final LocalOrderRepository orders;
    private final StubPartner partner;

    private volatile int failOnPage = -1;

    /**
     * Creates the task.
     *
     * @param orders  the local records
     * @param partner the stub partner
     */
    public PartnerCatalogueTask(LocalOrderRepository orders, StubPartner partner) {
        this.orders = orders;
        this.partner = partner;
    }

    /**
     * Makes the next sweep throw when it asks for the page starting at {@code offset}.
     *
     * @param offset the page offset to fail on, or -1 to stop failing
     */
    public void failOnPageAt(int offset) {
        this.failOnPage = offset;
    }

    @Override
    public String name() {
        return "partner-catalogue";
    }

    @Override
    public DemandProvider<LocalOrder, String> demand() {
        return new DemandProvider<>() {

            @Override
            @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
            public List<LocalOrder> demand(DemandRequest request) {
                // Empty demand: a catalogue sweep takes everything the partner publishes.
                return List.of();
            }

            @Override
            @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
            public Optional<LocalOrder> byKey(String key) {
                return orders.findByExternalId(key);
            }
        };
    }

    @Override
    public Function<LocalOrder, String> localKey() {
        return LocalOrder::getExternalId;
    }

    @Override
    public KeyCodec<String> keyCodec() {
        return KeyCodec.ofString();
    }

    @Override
    public Fetcher<String, BillingRecord> fetcher() {
        return new Fetcher.Paged<>() {

            @Override
            public PageResult<BillingRecord> fetchPage(String cursor, PageRequest request) {
                partner.recordPageCall();
                int offset = cursor == null ? 0 : Integer.parseInt(cursor);
                if (offset == failOnPage) {
                    throw new IllegalStateException("partner failed on page at offset " + offset);
                }
                List<BillingRecord> all = partner.catalogue();
                int end = Math.min(all.size(), offset + PAGE_SIZE);
                List<BillingRecord> page = all.subList(Math.min(offset, all.size()), end);
                return end >= all.size()
                        ? PageResult.last(page)
                        : PageResult.of(page, String.valueOf(end));
            }

            @Override
            public String keyOf(BillingRecord record) {
                return record.orderId();
            }
        };
    }

    @Override
    public Reconciler<LocalOrder, BillingRecord> reconciler() {
        return (local, external, context) -> {
            if (external.status().equals(local.getStatus())) {
                return ReconcileResult.unchanged();
            }
            local.setStatus(external.status());
            local.setSourceTimestamp(external.changedAt());
            local.setSyncCount(local.getSyncCount() + 1);
            orders.save(local);
            return ReconcileResult.applied("status -> " + external.status());
        };
    }

    @Override
    public Class<BillingRecord> externalType() {
        return BillingRecord.class;
    }

    @Override
    public ExternalStamp stampOf(BillingRecord record) {
        return ExternalStamp.ofTimestamp(record.changedAt());
    }
}
