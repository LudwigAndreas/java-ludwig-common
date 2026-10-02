package ru.ludwigandreas.fileaction.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.storage.api.ObjectStore;

/**
 * Picks up the submissions a {@code DEFERRED} action accepted and processes them.
 *
 * <h2>Why a lease rather than a queue</h2>
 *
 * <p>The submission row is the queue, claimed with a lease, which is the pattern {@code outbox} and
 * {@code reconciliation} already use and the reason {@code job-core} exists. A message queue would be a second
 * place a submission can be, and the two can disagree: a message with no row is work nobody can look up, and a
 * row with no message is work that never happens, and both are discovered by a user asking where their import
 * went.
 *
 * <p>The lease is what makes a pod dying mid-apply recoverable. The claim writes a holder and an expiry; an
 * instance that stops renewing loses the submission to the next tick of another instance, with no human
 * involved. That is also why the attempt counter exists: a submission that fails the same way three times is
 * reported rather than retried for ever.
 *
 * <h2>Why this does not hold a RunLock</h2>
 *
 * <p>Unlike the retention job, this one is not mutually exclusive across the cluster - it should not be. Several
 * instances processing different submissions at once is the point, and {@code claimForProcessing} is what keeps
 * two of them off the same one. A cluster-wide lock here would make the throughput of a whole deployment that of
 * one pod.
 */
public class DeferredSubmissionWorker {

    private static final Logger LOG = LoggerFactory.getLogger(DeferredSubmissionWorker.class);

    private final FileActionRegistry registry;
    private final FileActionSubmissionRepository submissions;
    private final SubmissionStore store;
    private final FileActionService service;
    private final ObjectStore objectStore;
    private final String owner;
    private final Duration lease;
    private final int batchSize;
    private final int maxAttempts;
    private final Clock clock;

    /**
     * Wires the worker.
     *
     * @param registry    the resolved actions
     * @param submissions the submission table
     * @param store       the state transitions
     * @param service     the read-and-apply the submit path also uses
     * @param objectStore where the submitted bytes are
     * @param owner       the string this instance writes into {@code locked_by}, from {@code ClaimOwner}
     * @param lease       how long a claim lasts before another instance may take it
     * @param batchSize   how many submissions one tick claims
     * @param maxAttempts how many times a submission is attempted before it is left alone
     * @param clock       the clock
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a worker needs its collaborators and its four tuning values, and
    // the values come from configuration rather than being grouped into a type nobody else reads.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeferredSubmissionWorker(FileActionRegistry registry,
                                    FileActionSubmissionRepository submissions, SubmissionStore store,
                                    FileActionService service, ObjectStore objectStore, String owner,
                                    Duration lease, int batchSize, int maxAttempts, Clock clock) {
        this.registry = registry;
        this.submissions = submissions;
        this.store = store;
        this.service = service;
        this.objectStore = objectStore;
        this.owner = owner;
        this.lease = lease;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    /**
     * One tick: claim what is due and process it.
     *
     * <p>Exceptions from one submission do not stop the others. A file that cannot be read is that submission's
     * problem, and letting it end the tick would mean one bad upload stalling every other deployment's import
     * until somebody noticed.
     */
    public void pollOnce() {
        List<FileActionSubmissionEntity> claimed = submissions.claimForProcessing(
                FileActionState.UPLOADED, owner, clock.instant(), clock.instant().plus(lease), batchSize);
        for (FileActionSubmissionEntity submission : claimed) {
            try {
                processOne(submission);
            } catch (FileRejectedException refused) {
                // Already recorded against the submission by the service; nothing further to do here. Logged
                // at debug rather than warn because a user's file being wrong is not an operational event.
                LOG.debug("submission {} was refused: {}", submission.getId(), refused.getCode());
            } catch (RuntimeException failed) {
                LOG.warn("submission {} could not be processed", submission.getId(), failed);
                store.transition(submission.getId(), FileActionState.REJECTED);
            }
        }
    }

    private void processOne(FileActionSubmissionEntity submission) {
        if (submission.getAttempts() >= maxAttempts) {
            // Left in REJECTED rather than retried for ever. A submission that has failed this many times is
            // failing for a reason a retry will not change, and a worker that kept trying would hide it.
            LOG.warn("submission {} has failed {} times and will not be attempted again",
                    submission.getId(), submission.getAttempts());
            store.transition(submission.getId(), FileActionState.REJECTED);
            return;
        }
        store.recordAttempt(submission.getId());
        ResolvedAction<?> action = registry.require(submission.getAction());
        Path workDir = createWorkDirectory();
        try {
            Path local = Files.createTempFile(workDir, "ludwig-file-action-", ".upload");
            try (InputStream stored = objectStore.open(submission.getObjectUri())) {
                // Streamed to a local file rather than into memory. The reader needs a local file - POI's
                // streaming path only reads lazily from one - and a ranged re-read would be the alternative,
                // which is work for no gain when the whole file is going to be read anyway.
                Files.copy(stored, local, StandardCopyOption.REPLACE_EXISTING);
            }
            SourceFormat format = submission.getSourceFormat();
            service.process(narrow(action), submission, local, format, workDir, true);
        } catch (IOException failed) {
            throw new UncheckedIOException(
                    "submission " + submission.getId() + " could not be fetched from storage", failed);
        } finally {
            deleteQuietly(workDir);
        }
    }

    /**
     * Narrows a wildcard action so it can be passed to the generic process method.
     *
     * <p>The cast is safe and unavoidable: the registry holds actions of many row types and the row type is only
     * known to the handler, so there is no signature the registry could expose that would carry it. The
     * alternative - a visitor per action - would add a type parameter to every caller for no checking anyone
     * benefits from, because every path through this worker is type-agnostic by construction.
     */
    @SuppressWarnings("unchecked")
    private static <R> ResolvedAction<R> narrow(ResolvedAction<?> action) {
        return (ResolvedAction<R>) action;
    }

    private static Path createWorkDirectory() {
        try {
            return Files.createTempDirectory("ludwig-file-action-worker-");
        } catch (IOException failed) {
            throw new UncheckedIOException("a working directory could not be created", failed);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (java.util.stream.Stream<Path> entries = Files.list(path)) {
                    entries.forEach(DeferredSubmissionWorker::deleteQuietly);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException leftBehind) {
            LOG.warn("could not clean up {}", path, leftBehind);
        }
    }
}
