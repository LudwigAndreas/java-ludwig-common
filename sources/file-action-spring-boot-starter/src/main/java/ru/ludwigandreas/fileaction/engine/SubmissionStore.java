package ru.ludwigandreas.fileaction.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.entity.FileActionRowRejectEntity;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.format.RowProblem;
import ru.ludwigandreas.fileaction.repository.FileActionRowRejectRepository;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;

/**
 * Every write to this module's two tables, and nothing else.
 *
 * <h2>Why the state transitions are gathered here</h2>
 *
 * <p>Each one is a short transaction of its own, deliberately separate from the long-running read and apply.
 * A submission moving to {@code APPLYING} must be visible to other instances <em>before</em> the apply starts -
 * otherwise a second instance claims it - and a submission moving to {@code APPLIED} must commit even though
 * the apply's own transaction has already committed and closed. Neither is possible if the transition shares a
 * transaction with the work.
 *
 * <p>{@code REQUIRES_NEW} on each one says so. Without it, a transition called from inside an apply would join
 * that transaction and be rolled back with it - so a failed apply would leave the submission in
 * {@code APPLYING} for ever, which is the state a lease exists to recover from and should not be the normal
 * outcome of a failure.
 */
public class SubmissionStore {

    private final FileActionSubmissionRepository submissions;
    private final FileActionRowRejectRepository rejects;
    private final Clock clock;

    /**
     * Prepares the store.
     *
     * @param submissions the submission table
     * @param rejects     the bounded reject sample
     * @param clock       the clock every timestamp comes from, injected so a test can control it and so that
     *                    nothing here calls {@code Clock.systemDefaultZone()}, which
     *                    {@code RuleGroup.PRESENTATION} forbids
     */
    public SubmissionStore(FileActionSubmissionRepository submissions,
                           FileActionRowRejectRepository rejects, Clock clock) {
        this.submissions = submissions;
        this.rejects = rejects;
        this.clock = clock;
    }

    /**
     * Records an accepted submission, before anything parses it.
     *
     * @param submission the row to save
     * @return the saved row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileActionSubmissionEntity create(FileActionSubmissionEntity submission) {
        Instant now = clock.instant();
        submission.setCreatedAt(now);
        submission.setUpdatedAt(now);
        if (submission.getSubmittedAt() == null) {
            submission.setSubmittedAt(now);
        }
        return submissions.save(submission);
    }

    /**
     * Moves a submission to a new state, recording the moment.
     *
     * @param id    the submission
     * @param state the new state
     * @return the updated row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileActionSubmissionEntity transition(UUID id, FileActionState state) {
        FileActionSubmissionEntity submission = submissions.getReferenceById(id);
        Instant now = clock.instant();
        if (submission.getStartedAt() == null && state == FileActionState.APPLYING) {
            submission.setStartedAt(now);
        }
        if (state.isTerminal()) {
            submission.setFinishedAt(now);
            // The lease is released on every terminal state, including a failure. A terminal submission that
            // kept its lease would be invisible to the claim query for the lease's duration and then claimed
            // again, which is how a failed apply becomes a repeated one.
            submission.setLockedBy(null);
            submission.setLeaseExpiresAt(null);
        }
        submission.setState(state);
        submission.setUpdatedAt(now);
        return submissions.save(submission);
    }

    /**
     * Records the outcome of a read-and-apply, and the state it leaves the submission in.
     *
     * @param id       the submission
     * @param state    the new state
     * @param counts   the row counts
     * @param failure  the refusal code, or null
     * @param failureArgs the refusal's arguments, newline-separated, or null
     * @return the updated row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileActionSubmissionEntity complete(UUID id, FileActionState state, RowCounts counts,
                                               String failure, String failureArgs) {
        FileActionSubmissionEntity submission = submissions.getReferenceById(id);
        Instant now = clock.instant();
        submission.setState(state);
        submission.setRowsRead(counts.read());
        submission.setRowsApplied(counts.applied());
        submission.setRowsRejected(counts.rejected());
        submission.setRowsSkipped(counts.skipped());
        submission.setFailureCode(failure);
        submission.setFailureArgs(failureArgs);
        submission.setUpdatedAt(now);
        if (state.isTerminal()) {
            submission.setFinishedAt(now);
            submission.setLockedBy(null);
            submission.setLeaseExpiresAt(null);
        }
        return submissions.save(submission);
    }

    /**
     * Stores the bounded reject sample for a submission.
     *
     * <p>Replaces whatever was there: a re-applied submission re-reads the file, so keeping the previous
     * attempt's rejects would show the user each row twice.
     *
     * @param submission the submission
     * @param sample     the rejects to keep
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceRejects(FileActionSubmissionEntity submission, java.util.List<RowProblem> sample) {
        rejects.deleteBySubmission(submission.getId());
        insert(submission, sample);
    }

    /**
     * Adds rejects to whatever a submission already has.
     *
     * <p>Needed by the confirm path, which stored the binding rejects at validation and may add handler rejects when
     * it applies. Replacing there would delete the cell-level problems the user was shown.
     *
     * @param submission the submission
     * @param sample     the rejects to add
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addRejects(FileActionSubmissionEntity submission, java.util.List<RowProblem> sample) {
        insert(submission, sample);
    }

    private void insert(FileActionSubmissionEntity submission, java.util.List<RowProblem> sample) {
        Instant now = clock.instant();
        for (RowProblem problem : sample) {
            rejects.save(FileActionRowRejectEntity.builder()
                    .submission(submission)
                    .sheet(problem.address().sheet())
                    .displayedRow(problem.address().row())
                    .columnHeader(problem.address().column())
                    .code(problem.code())
                    .args(FileActionRowRejectEntity.joinArguments(problem.args()))
                    .createdAt(now)
                    .build());
        }
    }

    /**
     * Records where a submission's artifacts ended up.
     *
     * @param id            the submission
     * @param boundRowsUri  the bound-row artifact, or null
     * @param errorReportUri the reject report, or null
     * @param expiresAt     when the artifacts become collectable, or the confirm window closes
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordArtifacts(UUID id, String boundRowsUri, String errorReportUri, Instant expiresAt) {
        FileActionSubmissionEntity submission = submissions.getReferenceById(id);
        if (boundRowsUri != null) {
            submission.setBoundRowsUri(boundRowsUri);
        }
        if (errorReportUri != null) {
            submission.setErrorReportUri(errorReportUri);
        }
        if (expiresAt != null) {
            submission.setExpiresAt(expiresAt);
        }
        submission.setUpdatedAt(clock.instant());
        submissions.save(submission);
    }

    /**
     * Asks a running submission to stop.
     *
     * <p>Sets a flag the apply checks between batches. It does not interrupt anything, which is why the cancel
     * endpoint answers 202: the stop is requested here and achieved a batch later.
     *
     * @param id the submission
     * @return the updated row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileActionSubmissionEntity requestCancellation(UUID id) {
        FileActionSubmissionEntity submission = submissions.getReferenceById(id);
        submission.setCancellationRequested(true);
        submission.setUpdatedAt(clock.instant());
        return submissions.save(submission);
    }

    /**
     * Clears a submission's stored artifact references, once retention has removed them.
     *
     * @param id the submission
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(UUID id) {
        FileActionSubmissionEntity submission = submissions.getReferenceById(id);
        submission.setState(FileActionState.EXPIRED);
        submission.setBoundRowsUri(null);
        submission.setErrorReportUri(null);
        submission.setFinishedAt(submission.getFinishedAt() == null
                ? clock.instant() : submission.getFinishedAt());
        submission.setUpdatedAt(clock.instant());
        submissions.save(submission);
    }

    /** Records that an attempt is starting, for the backoff a repeated failure gets. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(UUID id) {
        FileActionSubmissionEntity submission = submissions.getReferenceById(id);
        submission.setAttempts(submission.getAttempts() + 1);
        submission.setUpdatedAt(clock.instant());
        submissions.save(submission);
    }

    /** The row counts of one submission. */
    public record RowCounts(long read, long applied, long rejected, long skipped) {

        /** Counts from a bind that produced nothing. */
        public static RowCounts none() {
            return new RowCounts(0, 0, 0, 0);
        }
    }
}
