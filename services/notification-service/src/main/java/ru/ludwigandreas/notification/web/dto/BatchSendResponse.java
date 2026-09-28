package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What a batch submission produced, item by item.
 *
 * <p>Always 200, whatever the items did, and the counts below are why. A batch is a transport for
 * several independent requests, so there is no single status code that describes it: 202 would claim
 * every item was accepted, and a 4xx would claim none was. The HTTP status answers "was the batch
 * understood", the body answers "what happened to each item", and a caller that cares reads
 * {@link #rejected()} rather than the status line.
 *
 * @param accepted how many items produced a request
 * @param rejected how many did not. Non-zero with a 200 is the normal partial-success case
 * @param results  one entry per submitted item, in the order they were sent
 */
@Schema(description = "Per-item outcome of a batch submission")
public record BatchSendResponse(int accepted, int rejected, List<BatchItemResult> results) {

    public BatchSendResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static BatchSendResponse of(List<BatchItemResult> results) {
        int accepted = (int) results.stream().filter(BatchItemResult::isAccepted).count();
        return new BatchSendResponse(accepted, results.size() - accepted, results);
    }
}
