package ru.ludwigandreas.fileaction.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;

/**
 * The queries this module runs against its own submission table.
 *
 * <p>Every one of them is a QueryDSL expression against the generated Q-type. There is no SQL and no JPQL in
 * this module, and no new carve-out: the two that exist are {@code ru.ludwigandreas.ingest.bulk} and
 * {@code ru.ludwigandreas.idempotency.sql}, and {@code NoSqlStringsTest} fails this module's build if a third
 * appears here.
 */
public interface FileActionSubmissionQueryRepository {

    /**
     * Finds a submission by id, within an action.
     *
     * <p>Both, not just the id: the id alone would let a caller poll one action's submission through
     * another action's endpoint, which leaks whatever the envelope carries across an authorisation boundary
     * that is configured per action.
     *
     * @param action the action name from the path
     * @param id     the submission id
     * @return the submission, or empty
     */
    Optional<FileActionSubmissionEntity> findByActionAndId(String action, UUID id);

    /**
     * Finds the submission a repeated upload of the same content belongs to.
     *
     * @param action    the action
     * @param sha256    the content hash
     * @param submitter who uploaded it
     * @return the existing submission, or empty
     */
    Optional<FileActionSubmissionEntity> findByContent(String action, String sha256, String submitter);

    /**
     * Claims deferred submissions for this instance.
     *
     * <p>Takes the oldest submissions in {@code state} whose lease has lapsed, writes the holder and the new
     * lease, and returns what it took. The lease is what makes a pod dying mid-apply recoverable: the row
     * becomes claimable again without anybody intervening.
     *
     * @param state     the state to claim from
     * @param owner     the string identifying this instance
     * @param now       the current moment
     * @param leaseUntil when the new lease lapses
     * @param limit     how many to take
     * @return the claimed submissions
     */
    List<FileActionSubmissionEntity> claimForProcessing(FileActionState state, String owner, Instant now,
                                                        Instant leaseUntil, int limit);

    /**
     * Finds submissions whose artifacts are collectable, or whose confirm window has closed.
     *
     * @param now   the current moment
     * @param limit how many to take
     * @return the expired submissions
     */
    List<FileActionSubmissionEntity> findExpired(Instant now, int limit);

    /**
     * Whether a cancellation has been requested for a submission.
     *
     * <p>Read as its own query rather than off an entity the apply is holding, because the apply holds a
     * loaded entity for the length of a batch and the cancel is written by a different transaction on a
     * different thread. Re-reading the loaded entity's field would never see it.
     *
     * @param id the submission
     * @return true when somebody has asked it to stop
     */
    boolean isCancellationRequested(UUID id);
}
