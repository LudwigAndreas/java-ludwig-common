package ru.ludwigandreas.fileaction.engine;

import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/**
 * What the engine tells the web layer about a submission.
 *
 * <h2>Why the entity does not leave the engine</h2>
 *
 * <p>It did, and the shared architecture rules found it:
 * {@code web.controllers-do-not-expose-entities} and {@code web.controllers-do-not-use-persistence-types}. The
 * rules are right, and not only about layering. A JPA entity handed to a controller is a <em>detached</em> entity
 * outside the transaction that loaded it, so any association it carries throws on access, and the failure is a
 * {@code LazyInitializationException} in a response serialiser - which presents as a 500 on a request that
 * worked, at whatever moment somebody adds a field.
 *
 * <p>So the engine returns this instead: a flat record of what the web layer actually needs, read inside the
 * transaction that loaded the row.
 *
 * @param id            the submission, which is also the operation id a client polls
 * @param action        the configured action
 * @param state         this module's lifecycle state
 * @param filename      the name the client sent
 * @param format        what the content turned out to be
 * @param sheet         the sheet that was read, or null for a CSV
 * @param rowsRead      how many data rows were read
 * @param rowsApplied   how many rows the handler applied
 * @param rowsRejected  how many rows were refused
 * @param rowsSkipped   how many rows the handler deliberately ignored
 * @param failureCode   why a refused submission was refused, or null
 * @param failureArgs   the refusal message's arguments, newline-separated, or null
 * @param errorReportUri the reject report's object URI, or null
 * @param submittedAt   when it was accepted
 * @param startedAt     when work first started, or null
 * @param finishedAt    when it reached a terminal state, or null
 * @param expiresAt     when the confirm window closes or the artifacts become collectable, or null
 * @param correlationId the correlation id of the submitting request
 */
public record SubmissionSnapshot(UUID id, String action, FileActionState state, String filename,
                                 SourceFormat format, String sheet, long rowsRead, long rowsApplied,
                                 long rowsRejected, long rowsSkipped, String failureCode,
                                 String failureArgs, String errorReportUri, Instant submittedAt,
                                 Instant startedAt, Instant finishedAt, Instant expiresAt,
                                 String correlationId) {

    /**
     * Reads a row into a snapshot.
     *
     * @param submission the row, read inside the transaction that loaded it
     * @return the snapshot
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor, and this factory is the only
    // caller of it. Every component is a column the web layer reads.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static SubmissionSnapshot of(FileActionSubmissionEntity submission) {
        return new SubmissionSnapshot(submission.getId(), submission.getAction(), submission.getState(),
                submission.getDeclaredFilename(), submission.getSourceFormat(), submission.getSheet(),
                submission.getRowsRead(), submission.getRowsApplied(), submission.getRowsRejected(),
                submission.getRowsSkipped(), submission.getFailureCode(), submission.getFailureArgs(),
                submission.getErrorReportUri(), submission.getSubmittedAt(), submission.getStartedAt(),
                submission.getFinishedAt(), submission.getExpiresAt(), submission.getCorrelationId());
    }

    /** Whether nothing further will happen without a new request. */
    public boolean isTerminal() {
        return state != null && state.isTerminal();
    }

    /** Whether a reject report is available. */
    public boolean hasErrorReport() {
        return errorReportUri != null;
    }
}
