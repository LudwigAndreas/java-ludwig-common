package ru.ludwigandreas.notification.service.retention;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.IdempotencyRecordRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.RateLimitWindowRepository;
import ru.ludwigandreas.notification.repository.SuppressionRepository;

/**
 * Enforces the retention policy, one short transaction per step.
 *
 * <h2>The policy, and why it is four numbers rather than one</h2>
 *
 * <p>Different data is sensitive for different reasons and for different lengths of time, and one
 * retention period forces the privacy answer to be as long as the operational one.
 *
 * <ol>
 *   <li><b>Rendered bodies</b> ({@code content-ttl}, days) - the most sensitive thing here. Kept only
 *       as long as a customer might reasonably ask what we sent them.</li>
 *   <li><b>Recipient addresses and variable maps</b> ({@code recipient-data-ttl}, days) - scrubbed in
 *       place, leaving the delivery row and its operational columns intact.</li>
 *   <li><b>Delivery rows</b> ({@code delivery-ttl}, months) - by then carrying no personal data, and
 *       useful for capacity planning and for "how often does this template bounce".</li>
 *   <li><b>Status history</b> ({@code history-ttl}, longest) - the audit trail, which carries no
 *       personal data by construction and is what a complaint is answered from.</li>
 * </ol>
 *
 * <p>Each step is its own transaction, so a failure in one does not undo the others, and each runs
 * as a bounded statement rather than row by row - a purge that held one long transaction would pin
 * the oldest transaction id and stop autovacuum on the busiest table in the service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final SuppressionRepository suppressionRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final RateLimitWindowRepository rateLimitRepository;
    private final NotificationProperties properties;

    /** Drops rendered bodies whose own purge date has passed. */
    @Transactional
    public long purgeContent(Instant now) {
        return deliveryRepository.purgeContentDue(now);
    }

    /** Clears addresses and variable maps from deliveries that settled long enough ago. */
    @Transactional
    public long scrubRecipientData(Instant now) {
        return deliveryRepository.scrubRecipientDataBefore(
                now.minus(properties.getRetention().getRecipientDataTtl()));
    }

    /** Deletes settled deliveries; their content rows go with them by cascade. */
    @Transactional
    public long purgeDeliveries(Instant now) {
        return deliveryRepository.purgeSettledBefore(
                now.minus(properties.getRetention().getDeliveryTtl()));
    }

    /** Deletes the audit trail past its own, longer window. */
    @Transactional
    public long purgeHistory(Instant now) {
        return deliveryRepository.purgeHistoryBefore(
                now.minus(properties.getRetention().getHistoryTtl()));
    }

    /**
     * Compacts the suppression list.
     *
     * <p>Only entries with an expiry are removed. A permanent suppression - a hard bounce, a spam
     * complaint - has no expiry and is never compacted away: it does not stop being true, and
     * re-sending to a complainer is how a sending domain gets blocked for everybody.
     */
    @Transactional
    public long compactSuppressions(Instant now) {
        return suppressionRepository.purgeExpired(now);
    }

    /** Releases idempotency keys past their window, so a caller may reuse one. */
    @Transactional
    public long purgeIdempotencyKeys(Instant now) {
        return idempotencyRepository.purgeExpired(now);
    }

    /** Deletes rate-limit counters for windows that have closed. */
    @Transactional
    public long purgeRateLimitWindows(Instant now) {
        // Two windows of grace rather than exactly one: a poll cycle that started just before a
        // boundary releases its unused permits just after it, and deleting the row it is about to
        // touch would turn that release into a silent no-op.
        return rateLimitRepository.purgeWindowsBefore(
                now.minus(properties.getChannels().getRateLimitWindow().multipliedBy(2)));
    }
}
