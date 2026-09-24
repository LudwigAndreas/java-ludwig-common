package ru.ludwigandreas.reconciliation.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.api.DemandProvider;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.ReconcileContext;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.api.Reconciler;
import ru.ludwigandreas.reconciliation.api.SyncTask;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditLogger;
import ru.ludwigandreas.reconciliation.config.FetchShape;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.config.TaskSettingsResolver;
import ru.ludwigandreas.reconciliation.engine.RecordApplier;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.entity.SyncRecordKind;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;
import ru.ludwigandreas.reconciliation.metrics.NoopReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordApplierTest {

    /**
     * The external record type. A real type rather than a raw string, because the applier has to be
     * able to read a staged payload back into it - which is exactly what a task's externalType()
     * exists for.
     */
    record Invoice(String id, String status) {
    }


    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-01T11:00:00Z");

    private final SyncInboxRecordRepository repository = mock(SyncInboxRecordRepository.class);
    private final PayloadCodec codec = new PayloadCodec(new ObjectMapper());
    private final RecordApplier applier = new RecordApplier(repository, codec,
            new NoopReconciliationMetrics(), mock(ReconciliationAuditLogger.class));

    private final AtomicInteger reconcileCalls = new AtomicInteger();
    private final AtomicInteger missingCalls = new AtomicInteger();
    private final AtomicReference<ReconcileContext> lastContext = new AtomicReference<>();
    private final AtomicReference<ReconcileResult> nextResult =
            new AtomicReference<>(ReconcileResult.applied("changed"));
    private final AtomicReference<Optional<String>> localRecord =
            new AtomicReference<>(Optional.of("local"));

    @BeforeEach
    void noHistoryByDefault() {
        when(repository.findNewestSettled(anyString(), anyString(), any())).thenReturn(List.of());
    }

    @Test
    void appliesARecordAndReportsWhatChanged() {
        SyncInboxRecord record = staged("{\"status\":\"PAID\"}", ExternalStamp.ofTimestamp(LATER));

        SyncRecordStatus status = applier.apply(task(), record.getId());

        assertThat(status).isEqualTo(SyncRecordStatus.APPLIED);
        assertThat(reconcileCalls).hasValue(1);
        assertThat(record.getSettledAt()).isNotNull();
        assertThat(record.getLockedBy()).isNull();
    }

    /**
     * The idempotency short-circuit: an identical payload is settled without a write, without
     * updated_at churn and without calling the reconciler at all.
     */
    @Test
    void anIdenticalPayloadShortCircuitsToUnchangedWithoutCallingTheReconciler() {
        String payload = "{\"status\":\"PAID\"}";
        SyncInboxRecord previous = settled(payload, ExternalStamp.ofTimestamp(EARLIER));
        when(repository.findNewestSettled(anyString(), anyString(), any())).thenReturn(List.of(previous));

        SyncInboxRecord record = staged(payload, ExternalStamp.ofTimestamp(LATER));
        SyncRecordStatus status = applier.apply(task(), record.getId());

        assertThat(status).isEqualTo(SyncRecordStatus.UNCHANGED);
        assertThat(reconcileCalls).hasValue(0);
    }

    /**
     * The same record serialized with its keys in a different order is the same record. Without
     * normalization this would be applied again on every single poll.
     */
    @Test
    void aReorderedButIdenticalPayloadAlsoShortCircuits() {
        SyncInboxRecord previous = settled("{\"id\":\"A-1\",\"status\":\"PAID\"}",
                ExternalStamp.ofTimestamp(EARLIER));
        when(repository.findNewestSettled(anyString(), anyString(), any())).thenReturn(List.of(previous));

        SyncInboxRecord record = staged("{\"status\":\"PAID\",\"id\":\"A-1\"}",
                ExternalStamp.ofTimestamp(LATER));

        assertThat(applier.apply(task(), record.getId())).isEqualTo(SyncRecordStatus.UNCHANGED);
        assertThat(reconcileCalls).hasValue(0);
    }

    /**
     * Stale-write protection. Out-of-order responses are normal, and applying the older one last
     * silently regresses the local record - a bug with no error, no failed metric and no log line.
     */
    @Test
    void stateOlderThanWhatWasAlreadyAppliedIsRejected() {
        SyncInboxRecord previous = settled("{\"status\":\"REFUNDED\"}", ExternalStamp.ofTimestamp(LATER));
        when(repository.findNewestSettled(anyString(), anyString(), any())).thenReturn(List.of(previous));

        SyncInboxRecord record = staged("{\"status\":\"PAID\"}", ExternalStamp.ofTimestamp(EARLIER));
        SyncRecordStatus status = applier.apply(task(), record.getId());

        assertThat(status).isEqualTo(SyncRecordStatus.REJECTED);
        assertThat(reconcileCalls).hasValue(0);
        assertThat(record.getLastError()).contains("older than the state already applied");
    }

    @Test
    void newerStateIsApplied() {
        SyncInboxRecord previous = settled("{\"status\":\"PENDING\"}", ExternalStamp.ofTimestamp(EARLIER));
        when(repository.findNewestSettled(anyString(), anyString(), any())).thenReturn(List.of(previous));

        SyncInboxRecord record = staged("{\"status\":\"PAID\"}", ExternalStamp.ofTimestamp(LATER));

        assertThat(applier.apply(task(), record.getId())).isEqualTo(SyncRecordStatus.APPLIED);
    }

    @Test
    void theReconcilerSeesTheStampAlreadyAppliedForTheKey() {
        SyncInboxRecord previous = settled("{\"status\":\"PENDING\"}", ExternalStamp.ofTimestamp(EARLIER));
        when(repository.findNewestSettled(anyString(), anyString(), any())).thenReturn(List.of(previous));

        applier.apply(task(), staged("{\"status\":\"PAID\"}", ExternalStamp.ofTimestamp(LATER)).getId());

        assertThat(lastContext.get().lastAppliedStamp().timestamp()).isEqualTo(EARLIER);
        assertThat(lastContext.get().stamp().timestamp()).isEqualTo(LATER);
        assertThat(lastContext.get().isFirstApply()).isFalse();
    }

    @Test
    void aLocalRecordThatNoLongerExistsSettlesAsUnchangedRatherThanFailing() {
        localRecord.set(Optional.empty());

        SyncInboxRecord record = staged("{\"status\":\"PAID\"}", ExternalStamp.none());

        assertThat(applier.apply(task(), record.getId())).isEqualTo(SyncRecordStatus.UNCHANGED);
        assertThat(record.getLastError()).contains("no longer exists");
    }

    @Test
    void aMissingMarkerCallsTheReconcilersMissingBranch() {
        SyncInboxRecord record = staged(null, ExternalStamp.none());
        record.setKind(SyncRecordKind.MISSING);
        nextResult.set(ReconcileResult.applied("deactivated"));

        assertThat(applier.apply(task(), record.getId())).isEqualTo(SyncRecordStatus.APPLIED);
        assertThat(missingCalls).hasValue(1);
        assertThat(reconcileCalls).hasValue(0);
    }

    @Test
    void aDeferredResultKeepsTheRecordInThePipelineWithTheReconcilersOwnDelay() {
        nextResult.set(ReconcileResult.deferred(java.time.Duration.ofMinutes(5), "awaiting shipment"));

        SyncInboxRecord record = staged("{\"status\":\"PAID\"}", ExternalStamp.none());
        SyncRecordStatus status = applier.apply(task(), record.getId());

        assertThat(status).isEqualTo(SyncRecordStatus.DEFERRED);
        assertThat(record.getSettledAt()).isNull();
        assertThat(record.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(60));
    }

    @Test
    void aReconcilerThatThrowsFailsThatRecordAndSchedulesARetry() {
        nextResult.set(null);

        SyncInboxRecord record = staged("{\"status\":\"PAID\"}", ExternalStamp.none());
        SyncRecordStatus status = applier.apply(task(), record.getId());

        assertThat(status).isEqualTo(SyncRecordStatus.FAILED);
        assertThat(record.getAttempts()).isEqualTo(1);
        assertThat(record.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(record.getLastError()).contains("reconciler exploded");
    }

    @Test
    void aRecordThatHasExhaustedItsBudgetIsQuarantined() {
        nextResult.set(null);

        SyncInboxRecord record = staged("{\"status\":\"PAID\"}", ExternalStamp.none());
        record.setAttempts(7);
        record.setMaxAttempts(8);

        SyncRecordStatus status = applier.apply(task(), record.getId());

        assertThat(status).isEqualTo(SyncRecordStatus.QUARANTINED);
        assertThat(record.getSettledAt()).isNotNull();
    }

    private SyncInboxRecord staged(String payload, ExternalStamp stamp) {
        SyncInboxRecord record = new SyncInboxRecord();
        record.setId(UUID.randomUUID());
        record.setTaskName("billing-status");
        record.setKind(SyncRecordKind.RECORD);
        record.setCorrelationKey("A-1");
        record.setPayload(payload);
        record.setPayloadHash(payload == null ? null : codec.hashJson(payload));
        record.setExternalVersion(stamp.version());
        record.setExternalTimestamp(stamp.timestamp());
        record.setReceivedAt(Instant.now());
        record.setStatus(SyncRecordStatus.PROCESSING);
        record.setMaxAttempts(8);
        record.setNextAttemptAt(Instant.now());
        record.setLockedBy("instance-1");
        when(repository.getByIdOrThrow(record.getId())).thenReturn(record);
        return record;
    }

    private SyncInboxRecord settled(String payload, ExternalStamp stamp) {
        SyncInboxRecord record = new SyncInboxRecord();
        record.setId(UUID.randomUUID());
        record.setTaskName("billing-status");
        record.setCorrelationKey("A-1");
        record.setPayload(payload);
        record.setPayloadHash(codec.hashJson(payload));
        record.setExternalVersion(stamp.version());
        record.setExternalTimestamp(stamp.timestamp());
        record.setStatus(SyncRecordStatus.APPLIED);
        record.setSettledAt(Instant.now());
        return record;
    }

    private RegisteredTask<String, String, Invoice> task() {
        ReconciliationProperties properties = new ReconciliationProperties();
        ReconciliationProperties.Task configured = new ReconciliationProperties.Task();
        configured.getFetch().setShape(FetchShape.BATCHED);
        properties.getTasks().put("billing-status", configured);
        TaskSettings settings = TaskSettingsResolver.resolve("billing-status", configured, properties);
        return new RegisteredTask<>(new StubTask(), settings);
    }

    /** A task whose reconciler is driven by the fields above. */
    private final class StubTask implements SyncTask<String, String, Invoice> {

        @Override
        public String name() {
            return "billing-status";
        }

        @Override
        public DemandProvider<String, String> demand() {
            return new DemandProvider<>() {
                @Override
                public List<String> demand(DemandRequest request) {
                    return List.of();
                }

                @Override
                public Optional<String> byKey(String key) {
                    return localRecord.get();
                }
            };
        }

        @Override
        public Function<String, String> localKey() {
            return Function.identity();
        }

        @Override
        public KeyCodec<String> keyCodec() {
            return KeyCodec.ofString();
        }

        @Override
        public Fetcher<String, Invoice> fetcher() {
            return (Fetcher.Batched<String, Invoice>) keys -> java.util.Map.of();
        }

        @Override
        public Reconciler<String, Invoice> reconciler() {
            return new Reconciler<>() {
                @Override
                public ReconcileResult reconcile(String local, Invoice external, ReconcileContext context) {
                    reconcileCalls.incrementAndGet();
                    lastContext.set(context);
                    return result();
                }

                @Override
                public ReconcileResult reconcileMissing(String local, ReconcileContext context) {
                    missingCalls.incrementAndGet();
                    lastContext.set(context);
                    return result();
                }

                private ReconcileResult result() {
                    ReconcileResult result = nextResult.get();
                    if (result == null) {
                        throw new IllegalStateException("reconciler exploded");
                    }
                    return result;
                }
            };
        }

        @Override
        public Class<Invoice> externalType() {
            return Invoice.class;
        }

        @Override
        public ExternalStamp stampOf(Invoice record) {
            return ExternalStamp.none();
        }
    }
}
