package ru.ludwigandreas.reconciliation.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.CorrelationIdSource;
import ru.ludwigandreas.reconciliation.engine.DirectApplyService;
import ru.ludwigandreas.reconciliation.engine.FetchWalker;
import ru.ludwigandreas.reconciliation.engine.ManualRunCapacityException;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.RunRecorder;
import ru.ludwigandreas.reconciliation.engine.StagingService;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.entity.RunKind;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

/** A manual run refused for capacity holds nothing afterwards: no lease, no run row, nothing started. */
class ManualRunCapacityTest {

    private final RunLock lock = mock(RunLock.class);
    private final RunLockHandle lease = mock(RunLockHandle.class);
    private final RunRecorder recorder = mock(RunRecorder.class);
    private final UUID runId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private RegisteredTask<?, ?, ?> task() {
        TaskSettings settings = mock(TaskSettings.class);
        when(settings.name()).thenReturn("supplier-sync");
        when(settings.runTimeout()).thenReturn(Duration.ofMinutes(5));
        RegisteredTask<Object, Object, Object> task = mock(RegisteredTask.class);
        when(task.settings()).thenReturn(settings);
        return task;
    }

    private TaskRunner runner() {
        return new TaskRunner(lock, mock(FetchWalker.class), mock(StagingService.class),
                mock(DirectApplyService.class), mock(TaskStateService.class), mock(SyncInboxRecordRepository.class),
                mock(ReconciliationMetrics.class), mock(AuditSink.class), mock(CorrelationIdSource.class), recorder,
                work -> {
                    throw new TaskRejectedException("every manual-run slot is busy");
                });
    }

    @Test
    @DisplayName("a full executor refuses the run, releases the lease it took and discards the pending row")
    void refusedRunHoldsNothing() {
        when(lease.runId()).thenReturn(runId);
        when(lease.owner()).thenReturn("pod-a");
        when(lock.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));

        assertThatThrownBy(() -> runner().start(task(), DemandTier.HOT, RunKind.MANUAL, List.of(), "olga"))
                .isInstanceOf(ManualRunCapacityException.class)
                .satisfies(refused -> assertThat(((ManualRunCapacityException) refused).task())
                        .isEqualTo("supplier-sync"));

        verify(recorder).submitted(runId, "supplier-sync", DemandTier.HOT, RunKind.MANUAL, "olga", "pod-a");
        verify(recorder).discard(runId, "supplier-sync");
        verify(lease).close();
    }

    @Test
    @DisplayName("a lease held elsewhere is an empty answer, with nothing recorded")
    void leaseHeldElsewhere() {
        when(lock.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.empty());

        assertThat(runner().start(task(), DemandTier.HOT, RunKind.MANUAL, List.of(), "olga")).isEmpty();
        verify(recorder, org.mockito.Mockito.never()).submitted(any(), anyString(), any(), any(), any(), any());
    }
}
