package ru.ludwigandreas.notification.service.model;

/**
 * What a channel reports back about one send attempt.
 *
 * <p>A sealed result rather than an exception for the failure case, because a failed send is an
 * ordinary outcome here, not an exceptional one - the queue exists precisely because providers fail -
 * and because the interesting part of a failure is the {@link FailureClass}, which an exception type
 * hierarchy would have to encode in its shape and every channel would have to map onto.
 *
 * <p>Channels are still allowed to throw; the dispatcher treats an escaped exception as a retryable
 * failure, on the grounds that an unclassified failure is more likely a bug or an infrastructure
 * blip than a permanent rejection, and a retry that turns out to be pointless costs less than a
 * dead-lettered notification somebody was waiting for.
 */
public sealed interface DeliveryResult {

    /**
     * The provider accepted the message.
     *
     * @param providerMessageId the provider's own id, which is what an inbound receipt is matched
     *                          on later; null when the provider issues none
     */
    record Sent(String providerMessageId) implements DeliveryResult {
    }

    /**
     * The provider did not accept it.
     *
     * @param reason         operator-facing, already free of the recipient's address and the body
     * @param failureClass   whether another attempt could succeed
     */
    record Failed(String reason, FailureClass failureClass) implements DeliveryResult {

        public Failed {
            if (failureClass == null) {
                throw new IllegalArgumentException("A failed delivery must classify its failure");
            }
        }
    }

    static DeliveryResult sent(String providerMessageId) {
        return new Sent(providerMessageId);
    }

    static DeliveryResult retryable(String reason) {
        return new Failed(reason, FailureClass.RETRYABLE);
    }

    static DeliveryResult terminal(String reason) {
        return new Failed(reason, FailureClass.TERMINAL);
    }
}
