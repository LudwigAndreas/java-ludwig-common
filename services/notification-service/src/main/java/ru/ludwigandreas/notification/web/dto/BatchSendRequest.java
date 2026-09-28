package ru.ludwigandreas.notification.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Several notification requests in one call.
 *
 * <p>Exists because this platform has no broker yet, and a caller that would have produced a hundred
 * records to a topic should not have to open a hundred connections to replace it. It is a transport
 * optimisation and nothing more: each item goes through exactly the same application service, the
 * same validation and the same idempotency as a single submit, and an item submitted here is
 * indistinguishable afterwards from one submitted on its own.
 *
 * <h2>This is not "one request with many recipients"</h2>
 *
 * <p>The two look similar and mean different things. One request with many recipients is <em>one</em>
 * notification - one template, one category, one idempotency key - delivered to several people, and
 * it is atomic. A batch is several unrelated notifications that happen to be travelling together,
 * and each one succeeds or fails on its own. A caller that wants all-or-nothing wants the first
 * shape, which the single endpoint already provides.
 *
 * @param items the requests, each with an optional dedup key of its own. The batch as a whole has no
 *              key: it is not a unit of work, so there is nothing for one to identify
 */
public record BatchSendRequest(

        @NotEmpty(message = "{notification.validation.batch.required}")
        @Size(max = 500, message = "{notification.validation.batch.size}")
        List<@Valid BatchItem> items) {

    /**
     * One request inside a batch.
     *
     * <p>Composition rather than inheritance from {@link SendNotificationRequest}: the item is that
     * request plus a dedup key, and on the single endpoint the key travels in an
     * {@code Idempotency-Key} header. A header cannot carry one value per item, so here it moves into
     * the body - and it is a separate field rather than a field of the request precisely so the
     * single endpoint's contract, where the key is transport metadata, is not quietly changed.
     *
     * @param reference a caller-chosen label echoed back on the result. Not used for anything: it
     *                  exists so a caller can line results up with its own records without relying on
     *                  list order, which is a promise easier to make than to keep
     */
    public record BatchItem(

            @Size(max = 128, message = "{notification.validation.reference.size}")
            String reference,

            @Size(max = 255, message = "{notification.validation.idempotency-key.size}")
            String idempotencyKey,

            @NotNull(message = "{notification.validation.request.required}")
            @Valid
            SendNotificationRequest request) {
    }
}
