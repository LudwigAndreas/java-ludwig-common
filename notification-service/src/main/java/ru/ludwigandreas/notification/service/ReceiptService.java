package ru.ludwigandreas.notification.service;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.channel.HmacSigner;
import ru.ludwigandreas.notification.service.event.NotificationEventPublisher;
import ru.ludwigandreas.notification.service.exception.InvalidReceiptSignatureException;
import ru.ludwigandreas.notification.service.exception.UnknownReceiptException;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.DeliveryReceipt;
import ru.ludwigandreas.notification.service.model.ReceiptOutcome;
import ru.ludwigandreas.notification.service.preference.SuppressionService;
import ru.ludwigandreas.notification.service.queue.DeliveryStatusRecorder;

/**
 * Applies a provider callback: advances the delivery, and feeds the suppression list.
 *
 * <h2>Why receipts matter</h2>
 *
 * <p>"The SMTP server accepted it" and "it arrived in the mailbox" are different facts, and only the
 * first is observable at send time. Without receipts every delivery stops at {@code SENT}, a bounce
 * is invisible, the bad address stays on the list, and the sending domain's reputation pays for it
 * on behalf of every other recipient. This endpoint is what closes that loop.
 *
 * <h2>Why it is signed and timestamped</h2>
 *
 * <p>The endpoint has to be reachable by a provider, which means reachable by anybody, and a forged
 * bounce suppresses a real address - so an unauthenticated receipt endpoint is a denial-of-service
 * against individual recipients. Verification covers {@code <timestamp>.<body>} rather than the body
 * alone, because a signature over the body is replayable forever: a bounce captured once could be
 * replayed months later to suppress that address at a moment of the attacker's choosing.
 *
 * <p>Failures say nothing about which check failed. An endpoint that distinguishes "bad signature"
 * from "stale timestamp" from "unknown message" is an oracle for probing all three.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptService {

    /** Longest provider message id accepted, matching the column it is looked up against. */
    private static final int MAX_MESSAGE_ID_LENGTH = 255;

    private final ObjectMapper objectMapper;
    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryStatusRecorder statusRecorder;
    private final SuppressionService suppressionService;
    private final NotificationEventPublisher eventPublisher;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    /**
     * Verifies, parses and applies one inbound callback.
     *
     * <p>The whole sequence lives here rather than half in the controller, for two reasons. The
     * signature covers the exact bytes the provider sent, so the body has to be verified before it is
     * parsed - which means the parse is part of the same operation, not a binding step the framework
     * can do first. And parsing a body means handling a checked {@code JacksonException}, which a
     * controller is not allowed to catch: an entry point that swallows a failure is how a rejected
     * request becomes a silently accepted one.
     *
     * <p>This is the one endpoint in the service with two models rather than three: the payload is
     * deserialized straight into {@link DeliveryReceipt}. A web DTO would have to live in the web
     * layer, and a service that imported it would be reaching into an entry point - a worse coupling
     * than the one the extra layer buys. The shape is dictated by the providers anyway, so there is no
     * internal model for it to drift from; the published schema is in the controller's OpenAPI
     * description.
     *
     * <p>Transactional at this level rather than on {@link #apply}: {@code accept} calls it directly,
     * and Spring's proxy does not intercept a bean calling its own method - so the annotation there
     * alone would leave the whole receipt running with no transaction, and the first
     * {@code MANDATORY} collaborator it reached would refuse. Verification and parsing touch no
     * database, so covering them costs nothing.
     *
     * @param rawBody the request body exactly as received
     */
    @Transactional
    public void accept(String rawBody, String signature, String timestampHeader) {
        verify(rawBody, signature, timestampHeader);
        apply(parse(rawBody));
    }

    /**
     * Parses an already-verified body.
     *
     * <p>A malformed payload from a correctly-signed caller is a bug in the provider's integration
     * rather than an attack, so it is worth logging precisely - but it is still answered with the same
     * opaque rejection, because at this point the caller is only proven to hold the secret.
     */
    private DeliveryReceipt parse(String rawBody) {
        DeliveryReceipt receipt;
        try {
            receipt = objectMapper.readValue(rawBody, DeliveryReceipt.class);
        } catch (JacksonException e) {
            log.warn("A correctly signed receipt could not be parsed", e);
            throw new InvalidReceiptSignatureException();
        }
        if (receipt == null || receipt.channel() == null || receipt.outcome() == null
                || receipt.providerMessageId() == null || receipt.providerMessageId().isBlank()
                || receipt.providerMessageId().length() > MAX_MESSAGE_ID_LENGTH) {
            // Checked explicitly rather than with Bean Validation, because this payload cannot go
            // through @Valid parameter binding - see accept(). Skipping validation because the
            // transport made it awkward would leave the most attackable surface here unchecked.
            log.warn("A correctly signed receipt was malformed");
            throw new InvalidReceiptSignatureException();
        }
        // A provider that reports no timestamp is treated as reporting now; the alternative is a null
        // that would silently become the epoch on the delivery.
        return receipt.occurredAt() != null ? receipt : new DeliveryReceipt(receipt.channel(),
                receipt.providerMessageId(), receipt.outcome(), Instant.now(), receipt.detail());
    }

    /**
     * Verifies an inbound callback.
     *
     * @param rawBody   the request body exactly as received - re-serializing a parsed object would
     *                  produce different bytes and every signature would fail
     * @throws InvalidReceiptSignatureException if the signature or the timestamp does not hold up
     */
    void verify(String rawBody, String signature, String timestampHeader) {
        NotificationProperties.Receipts settings = properties.getReceipts();
        if (settings.getSigningSecret() == null || settings.getSigningSecret().isBlank()) {
            // Refusing rather than accepting: a receipt endpoint with no configured secret that
            // waved callbacks through would be the single most dangerous default in this service.
            log.error("A receipt arrived but no signing secret is configured; refusing it");
            throw new InvalidReceiptSignatureException();
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(String.valueOf(timestampHeader).trim());
        } catch (NumberFormatException e) {
            throw new InvalidReceiptSignatureException();
        }

        Duration skew = Duration.between(Instant.ofEpochSecond(timestamp), Instant.now()).abs();
        if (skew.compareTo(settings.getTimestampTolerance()) > 0
                || !HmacSigner.verify(settings.getSigningSecret(), timestamp, rawBody, signature)) {
            throw new InvalidReceiptSignatureException();
        }
    }

    /**
     * Applies a verified receipt.
     *
     * <p>Idempotent: providers redeliver, and applying the same {@code DELIVERED} twice must not
     * produce two lifecycle events or two history entries. The guard is the current status - a
     * delivery already in the state a receipt describes is left alone.
     */
    @Transactional
    void apply(DeliveryReceipt receipt) {
        NotificationDeliveryEntity delivery = deliveryRepository
                .findByProviderMessageId(ChannelKind.valueOf(receipt.channel().name()),
                        receipt.providerMessageId())
                .orElseThrow(() -> new UnknownReceiptException(receipt.providerMessageId()));

        metrics.recordReceipt(receipt.channel(), receipt.outcome().name());
        switch (receipt.outcome()) {
            case DELIVERED -> applyDelivered(delivery, receipt);
            case BOUNCED, COMPLAINED -> applyPermanentFailure(delivery, receipt);
            case DEFERRED -> applyTemporaryFailure(delivery, receipt);
            default -> log.warn("Unhandled receipt outcome {}", receipt.outcome());
        }
    }

    private void applyDelivered(NotificationDeliveryEntity delivery, DeliveryReceipt receipt) {
        if (delivery.getStatus() == DeliveryStatus.DELIVERED) {
            return;
        }
        delivery.setDeliveredAt(receipt.occurredAt());
        statusRecorder.transition(delivery, DeliveryStatus.DELIVERED, "confirmed by the provider");
        eventPublisher.publishDelivered(delivery);
    }

    /**
     * A hard bounce or a spam complaint.
     *
     * <p>Suppresses permanently and dead-letters the delivery. A complaint in particular is not about
     * one message - it is the recipient telling the mailbox provider that this sender is unwanted, and
     * continuing to send anything to that address after it damages deliverability for every other
     * recipient on the domain.
     */
    private void applyPermanentFailure(NotificationDeliveryEntity delivery, DeliveryReceipt receipt) {
        String reason = receipt.outcome() == ReceiptOutcome.COMPLAINED ? "complaint" : "hard-bounce";
        suppress(delivery, receipt, reason, null);

        if (delivery.getStatus() != DeliveryStatus.DEAD) {
            delivery.setLastError(reason + ": " + receipt.detail());
            statusRecorder.transition(delivery, DeliveryStatus.DEAD, reason);
            eventPublisher.publishFailed(delivery, reason);
        }
    }

    /**
     * A soft bounce: a full mailbox, greylisting, a temporary rejection.
     *
     * <p>Suppressed for a bounded window rather than forever, because the condition is temporary by
     * definition - and the delivery is left where it is, so an in-flight retry can still succeed once
     * the window lapses.
     */
    private void applyTemporaryFailure(NotificationDeliveryEntity delivery, DeliveryReceipt receipt) {
        Duration window = properties.getReceipts().getSoftBounceSuppression();
        suppress(delivery, receipt, "soft-bounce", window == null ? null : Instant.now().plus(window));
    }

    private void suppress(NotificationDeliveryEntity delivery, DeliveryReceipt receipt, String reason,
                          Instant expiresAt) {
        if (delivery.getRecipientAddress() == null) {
            // The retention purge has already scrubbed the address, which means the receipt is older
            // than the recipient-data window. Nothing to suppress, and nothing is wrong.
            return;
        }
        suppressionService.suppress(ChannelType.valueOf(delivery.getChannel().name()),
                delivery.getRecipientAddress(), reason, receipt.detail(), expiresAt);
    }
}
