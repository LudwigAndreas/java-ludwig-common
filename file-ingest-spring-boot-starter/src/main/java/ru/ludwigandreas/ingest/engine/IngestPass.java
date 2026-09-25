package ru.ludwigandreas.ingest.engine;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.api.IngestRunSummary;
import ru.ludwigandreas.ingest.audit.IngestAuditEvent;
import ru.ludwigandreas.ingest.audit.IngestAuditLogger;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.entity.FileIngestRun;
import ru.ludwigandreas.ingest.event.IngestEventPublisher;
import ru.ludwigandreas.ingest.metrics.IngestMetrics;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;
import ru.ludwigandreas.ingest.repository.IngestRunQueries;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.storage.api.ObjectSummary;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.api.StoredObject;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;

/**
 * One scheduled look for one task: discover, confirm arrival, claim, ingest.
 *
 * <h2>Everything is inside the lock, including the discovery</h2>
 *
 * <p>It would be cheaper to list first and take the lock only when there is something to do, and it
 * would be wrong: two replicas listing concurrently both find the object, both confirm its arrival,
 * and both then contend for the lock - which one of them wins, having already done the work. Worse,
 * the loser has already read a sentinel that may by then have been consumed. Taking the lock first
 * means exactly one replica does any of it, which is what {@code runIfAvailable} is for.
 *
 * <p>The identity constraint is still the guarantee. The lock is what stops two replicas doing the
 * same work at the same time; the constraint is what stops the work being done twice at all, including
 * across a lock that expired because an instance died. Neither replaces the other - see the run
 * entity's note.
 */
@Slf4j
public class IngestPass {

    private final IngestTaskRegistry registry;
    private final ObjectDiscovery discovery;
    private final ArrivalDetector arrival;
    private final IngestRunner runner;
    private final FileIngestRunRepository runs;
    private final ru.ludwigandreas.storage.api.ObjectStore store;
    private final RunLock lock;
    private final IngestMetrics metrics;
    private final IngestAuditLogger audit;
    private final IngestEventPublisher events;
    private final Clock clock;

    /**
     * Creates the pass.
     *
     * @param registry  the configured tasks
     * @param discovery object discovery
     * @param arrival   arrival detection
     * @param runner    the run loop
     * @param runs      the run table
     * @param store     where the source objects are
     * @param lock      the platform's one distributed lock
     * @param metrics   the metrics
     * @param audit     the audit sink
     * @param events    the completion publisher
     * @param clock     the clock
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IngestPass(IngestTaskRegistry registry, ObjectDiscovery discovery, ArrivalDetector arrival,
                      IngestRunner runner, FileIngestRunRepository runs,
                      ru.ludwigandreas.storage.api.ObjectStore store, RunLock lock,
                      IngestMetrics metrics, IngestAuditLogger audit, IngestEventPublisher events,
                      Clock clock) {
        this.registry = registry;
        this.discovery = discovery;
        this.arrival = arrival;
        this.runner = runner;
        this.runs = runs;
        this.store = store;
        this.lock = lock;
        this.metrics = metrics;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The lock name for a task, which is also what an operator greps for.
     *
     * @param task the task name
     * @return the lock name
     */
    public static String lockName(String task) {
        return "ludwig-file-ingest-" + task;
    }

    /**
     * Runs one pass for one task.
     *
     * @param taskName the task
     * @return {@code true} if this instance held the lock and did the pass
     */
    public boolean runOnce(String taskName) {
        RegisteredIngestTask task = registry.find(taskName).orElseThrow(
                () -> new IllegalStateException("No registered ingest task called " + taskName));
        if (!task.settings().isEnabled()) {
            return false;
        }
        return lock.runIfAvailable(lockName(taskName), task.settings().getLock().getLease(),
                handle -> ingestAvailable(task, handle));
    }

    private void ingestAvailable(RegisteredIngestTask task,
                                 ru.ludwigandreas.job.core.lock.RunLockHandle handle) {
        FileIngestProperties.Task settings = task.settings();
        List<ObjectSummary> candidates = discovery.discover(settings.getSource(), settings.getArrival(),
                settings.getSource().getMaxObjectsPerPass());
        if (candidates.isEmpty()) {
            // DEBUG, and that is exactly why ludwig.ingest.missing exists: for a once-a-day job the
            // failure nobody notices is the file that never came, and this line is the only trace of
            // it. The metric is what makes the absence visible; see IngestMetrics.
            log.debug("Ingest task {} found nothing under {}", task.name(), settings.getSource().getUri());
            return;
        }
        for (ObjectSummary candidate : candidates) {
            ingestOne(task, handle, candidate);
        }
    }

    private void ingestOne(RegisteredIngestTask task,
                           ru.ludwigandreas.job.core.lock.RunLockHandle handle,
                           ObjectSummary summary) {
        ObjectUri uri = ObjectUri.parse(summary.uri());
        StoredObject metadata;
        try {
            metadata = store.head(uri.value());
        } catch (ObjectNotFoundException e) {
            log.debug("{} disappeared between listing and reading", uri.value());
            return;
        }

        ArrivalDetector.Arrival verdict = arrival.check(uri, metadata, task.settings().getArrival());
        if (!verdict.ready()) {
            log.info("Ingest task {} is not reading {} yet: {}", task.name(), uri.value(),
                    verdict.reason());
            return;
        }

        ObjectCandidate object = new ObjectCandidate(uri, metadata, verdict.expectedRecords());
        Optional<FileIngestRun> existing = runs.findOne(IngestRunQueries.byIdentity(
                uri.container(), uri.key(), object.contentIdentity()));
        if (existing.filter(run -> run.getStatus() == IngestRunStatus.COMPLETED).isPresent()) {
            // Already done. Reached both by an ordinary re-offer and by a crash between COMPLETED and
            // the archive: the second case falls through to finishing the idempotent bucket
            // operations rather than re-reading anything.
            log.info("Ingest task {} has already ingested {} ({}); skipping",
                    task.name(), uri.value(), object.contentIdentity());
            metrics.recordSkippedAlreadyProcessed(task.name());
            audit.record(new IngestAuditEvent(clock.instant(), existing.get().getId(), task.name(),
                    IngestAuditEvent.SKIPPED_ALREADY_PROCESSED, Map.of("uri", uri.value())));
            return;
        }

        IngestRunSummary result = runner.ingest(task, object, handle);
        // Published after the run is COMPLETED, and through the outbox, so the event and the status
        // commit together - see OutboxIngestEventPublisher.
        events.completed(result);
    }
}
