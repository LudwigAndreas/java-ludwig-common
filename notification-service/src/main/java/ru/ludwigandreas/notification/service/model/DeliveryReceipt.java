package ru.ludwigandreas.notification.service.model;

import java.time.Instant;

/**
 * A provider telling us what became of a message after it accepted it.
 *
 * <p>"Accepted by the SMTP server" and "arrived in the mailbox" are different facts, and only the
 * first one is observable at send time. Without receipts a delivery sits in {@code SENT} forever and
 * a bounce is invisible, which means the address stays on the list and the sending domain's
 * reputation pays for it.
 *
 * @param providerMessageId the id the channel returned at send time; how the receipt finds its row
 * @param occurredAt        when the provider says it happened, which is not when we were told
 */
public record DeliveryReceipt(
        ChannelType channel,
        String providerMessageId,
        ReceiptOutcome outcome,
        Instant occurredAt,
        String detail) {
}
