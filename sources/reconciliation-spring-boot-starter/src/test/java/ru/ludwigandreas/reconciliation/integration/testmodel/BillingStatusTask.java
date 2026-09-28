package ru.ludwigandreas.reconciliation.integration.testmodel;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.reconciliation.api.DemandProvider;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.ReconcileContext;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.api.Reconciler;
import ru.ludwigandreas.reconciliation.api.ReconciliationTask;
import ru.ludwigandreas.reconciliation.api.SyncTask;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The batched task the main integration test drives: the shape most integrations actually are.
 *
 * <p>Written the way the README tells a service author to write one - a demand query, a fetcher and a
 * reconciler, and nothing operational - so that the test is also a check that the documented shape is
 * the shape that works.
 */
@ReconciliationTask("billing-status")
public class BillingStatusTask implements SyncTask<LocalOrder, String, BillingRecord> {

    private final LocalOrderRepository orders;
    private final StubPartner partner;

    /**
     * Creates the task.
     *
     * @param orders  the local records
     * @param partner the stub partner
     */
    public BillingStatusTask(LocalOrderRepository orders, StubPartner partner) {
        this.orders = orders;
        this.partner = partner;
    }

    @Override
    public String name() {
        return "billing-status";
    }

    @Override
    public DemandProvider<LocalOrder, String> demand() {
        return new DemandProvider<>() {

            @Override
            @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
            public List<LocalOrder> demand(DemandRequest request) {
                // The hot half is what has not settled; the cold sweep takes everything.
                return request.tier() == DemandTier.HOT
                        ? orders.findByStatusNot("SETTLED")
                        : orders.findAll();
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
        return (Fetcher.Batched<String, BillingRecord>) partner::fetchBatch;
    }

    @Override
    public Reconciler<LocalOrder, BillingRecord> reconciler() {
        return new Reconciler<>() {

            @Override
            public ReconcileResult reconcile(LocalOrder local, BillingRecord external,
                                             ReconcileContext context) {
                if ("EXPLODE".equals(external.status())) {
                    throw new IllegalStateException("this record cannot be mapped");
                }
                if (external.status().equals(local.getStatus())) {
                    return ReconcileResult.unchanged();
                }
                local.setStatus(external.status());
                local.setSourceTimestamp(external.changedAt());
                local.setSyncCount(local.getSyncCount() + 1);
                orders.save(local);
                return ReconcileResult.applied("status -> " + external.status());
            }

            @Override
            public ReconcileResult reconcileMissing(LocalOrder local, ReconcileContext context) {
                local.setStatus("GONE_UPSTREAM");
                orders.save(local);
                return ReconcileResult.applied("marked gone upstream");
            }
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
