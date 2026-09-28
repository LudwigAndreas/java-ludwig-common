package ru.ludwigandreas.reconciliation.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.api.FetchOutcome;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.RunContext;
import ru.ludwigandreas.reconciliation.engine.StagingService;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.quota.QuotaRegistry;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The collect pass: walks a finished job's result into the staging table.
 *
 * <p>Collection is the paged walk again, deliberately: {@code collect} returns a
 * {@link PageResult}, so a large result checkpoints and resumes exactly like a catalogue sweep
 * instead of being an all-or-nothing download that starts over on every interruption. For a result
 * measured in hundreds of thousands of rows that is the difference between a collection that finishes
 * and one that does not.
 *
 * <p>The cursor is written after the page it describes has been staged, never before - the other
 * order loses a page on any crash between the two writes, silently, by resuming past records that
 * were never written down.
 */
public class RemoteJobCollectService {

    private static final Logger log = LoggerFactory.getLogger(RemoteJobCollectService.class);

    private final SyncRemoteJobRepository jobs;
    private final StagingService staging;
    private final RemoteJobSettlement settlement;
    private final QuotaRegistry quotas;
    private final String owner;
    private final TransactionTemplate requiresNew;

    /**
     * Creates the service.
     *
     * @param jobs               the job table
     * @param staging            writes collected records down
     * @param settlement         terminal-state handling
     * @param quotas             partner-scoped concurrency budgets, renewed while collecting
     * @param owner              this instance's identity
     * @param transactionManager the transaction manager each commit runs against
     */
    public RemoteJobCollectService(SyncRemoteJobRepository jobs,
                                   StagingService staging,
                                   RemoteJobSettlement settlement,
                                   QuotaRegistry quotas,
                                   String owner,
                                   PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.staging = staging;
        this.settlement = settlement;
        this.quotas = quotas;
        this.owner = owner;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs one collect pass.
     *
     * @param task the task
     * @return how many jobs were collected from
     */
    public int collectPass(RegisteredTask<?, ?, ?> task) {
        return collectTyped(task);
    }

    private <I, K, O> int collectTyped(RegisteredTask<I, K, O> task) {
        TaskSettings settings = task.settings();
        TaskSettings.JobSettings jobSettings = settings.jobOrEmpty().orElseThrow();
        if (!(task.task().fetcher() instanceof Fetcher.JobFetcher)) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        Fetcher.JobFetcher<K, Object, O> fetcher = (Fetcher.JobFetcher<K, Object, O>) task.task().fetcher();

        List<SyncRemoteJob> claimed =
                jobs.claimForCollect(settings.name(), jobSettings.collectConcurrency(), owner);
        for (SyncRemoteJob job : claimed) {
            collectOne(task, fetcher, job, settings);
        }
        return claimed.size();
    }

    private <I, K, O> void collectOne(RegisteredTask<I, K, O> task,
                                      Fetcher.JobFetcher<K, Object, O> fetcher,
                                      SyncRemoteJob job,
                                      TaskSettings settings) {
        RunContext context = new RunContext(settings.name(), DemandTier.HOT, UUID.randomUUID(),
                null, Instant.now(), job.getExpiresAt());
        Object handle = fetcher.handleCodec().decode(job.getExternalHandle());
        String cursor = job.getCollectCursor();
        try {
            boolean more = true;
            while (more) {
                quotas.adopt(job.getQuotaLeaseId()).ifPresent(lease -> {
                    if (!lease.renew()) {
                        log.warn("Task '{}' lost the quota lease for job {} while collecting",
                                settings.name(), job.getId());
                    }
                });
                PageResult<O> page = fetcher.collect(handle, cursor);
                staging.stage(task, outcomes(fetcher, page), context, job.getId());

                cursor = page.nextCursor();
                more = page.hasMore() && cursor != null;
                checkpoint(job.getId(), more ? cursor : null);
            }
            settlement.settle(task, job.getId(), RemoteJobState.COLLECTED, null);
        } catch (Exception e) {
            // The job stays COLLECTING with its cursor where it got to, so the next pass resumes
            // rather than restarting. It is not settled: the result is still there, and the lease
            // still has to be held until it has been read or the job's lifetime runs out.
            log.warn("Task '{}' could not finish collecting job {}; it resumes from the checkpoint",
                    settings.name(), job.getId(), e);
            requiresNew.executeWithoutResult(status -> {
                SyncRemoteJob stored = jobs.getByIdOrThrow(job.getId());
                stored.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                jobs.save(stored);
            });
        }
    }

    /**
     * Turns a collected page into outcomes.
     *
     * <p>A collected record is addressed exactly the way a paged one is: by asking the fetcher for its
     * key. That is also why {@code collect} returns a {@link PageResult} rather than a map - a job's
     * result is a catalogue that happened to be produced on request.
     */
    private <K, O> List<FetchOutcome<K, O>> outcomes(Fetcher.JobFetcher<K, Object, O> fetcher,
                                                     PageResult<O> page) {
        List<FetchOutcome<K, O>> outcomes = new ArrayList<>(page.items().size());
        for (O record : page.items()) {
            outcomes.add(FetchOutcome.found(fetcher.keyOf(record), record));
        }
        return outcomes;
    }

    private void checkpoint(UUID jobId, String cursor) {
        requiresNew.executeWithoutResult(status -> {
            SyncRemoteJob stored = jobs.getByIdOrThrow(jobId);
            stored.setCollectCursor(cursor);
            jobs.save(stored);
        });
    }
}
