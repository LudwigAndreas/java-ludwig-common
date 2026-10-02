package ru.ludwigandreas.fileaction.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.webcore.operation.OperationResponse;

/**
 * What this module tells a client about one submission, alongside the platform envelope.
 *
 * <h2>Why both this and an {@code OperationResponse}</h2>
 *
 * <p>The long-running-operation contract is explicit that a module publishes its own response <em>alongside</em>
 * the envelope rather than instead of it, and this is why. What a client of an import actually branches on is in
 * here: how many rows were read, how many applied, how many were refused, whether there is a reject report to
 * download, and whether somebody has to press confirm. None of that is expressible in six status constants, and
 * flattening it into them would make the envelope useless to this module's clients while making this module
 * unpollable by a client that does not care which module it is talking to.
 *
 * <p>So the envelope is nested, and a generic client reads {@link #operation()} and ignores the rest.
 *
 * @param operation    the platform envelope, which a client that polls several modules the same way reads
 * @param id           the submission
 * @param action       the configured action
 * @param state        this module's own lifecycle state, which is also the envelope's {@code detail}
 * @param filename     the name the client sent
 * @param format       what the content turned out to be
 * @param sheet        the sheet that was read, or null for a CSV
 * @param rowsRead     how many data rows were read
 * @param rowsApplied  how many rows the handler applied
 * @param rowsRejected how many rows were refused
 * @param rowsSkipped  how many rows the handler deliberately ignored
 * @param rejectsStored how many of the refused rows the paged endpoint can serve. Compared with
 *                     {@code rowsRejected}, this is how a user interface knows to say "showing the first
 *                     hundred of three thousand"
 * @param awaitingConfirmation whether a person has to confirm before anything is applied
 * @param confirmBy    when the confirm window closes, or null
 * @param errorReport  whether a report is available to download
 * @param submittedAt  when it was accepted
 * @param finishedAt   when it reached a terminal state, or null
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionResponse(OperationResponse operation, UUID id, String action,
                                 FileActionState state, String filename, SourceFormat format, String sheet,
                                 long rowsRead, long rowsApplied, long rowsRejected, long rowsSkipped,
                                 long rejectsStored, boolean awaitingConfirmation, Instant confirmBy,
                                 boolean errorReport, Instant submittedAt, Instant finishedAt) {
}
