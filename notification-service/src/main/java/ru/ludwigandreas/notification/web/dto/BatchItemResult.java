package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ProblemDetail;

/**
 * What became of one item in a batch.
 *
 * <p>Exactly one of {@link #request} and {@link #problem} is set. The failure is a
 * {@link ProblemDetail} rather than a string because it is the same object the single endpoint would
 * have returned for the same input, produced by the same exception mapping - so a caller writes one
 * error handler rather than two, and a localized, typed problem does not degrade into a message when
 * it travels in a batch.
 *
 * @param index     position in the submitted list, so a result can be attributed even if a caller
 *                  supplied no reference
 * @param reference the caller's own label, echoed back untouched
 */
@Schema(description = "The outcome of one item in a batch submission")
public record BatchItemResult(
        int index,
        String reference,
        NotificationRequestResponse request,
        ProblemDetail problem) {

    public boolean isAccepted() {
        return request != null;
    }

    public static BatchItemResult accepted(int index, String reference,
                                           NotificationRequestResponse request) {
        return new BatchItemResult(index, reference, request, null);
    }

    public static BatchItemResult rejected(int index, String reference, ProblemDetail problem) {
        return new BatchItemResult(index, reference, null, problem);
    }
}
