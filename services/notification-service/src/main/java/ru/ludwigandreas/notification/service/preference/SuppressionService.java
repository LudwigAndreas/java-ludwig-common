package ru.ludwigandreas.notification.service.preference;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.SuppressionRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.SuppressionEntity;
import ru.ludwigandreas.notification.service.Pii;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.SuppressionView;

/**
 * The list of destinations this service will not write to, and the one rule with no bypass.
 *
 * <p>A suppression is a fact about the address rather than a preference of its owner, which is why a
 * {@link ru.ludwigandreas.notification.service.model.CategoryClass#TRANSACTIONAL} notification
 * honours it too. Continuing to send to an address that hard-bounced or filed a spam complaint
 * damages the sending domain's standing with the mailbox provider, and that cost is paid by every
 * other recipient on the same domain - so one recipient's undeliverable password reset is the cheaper
 * of the two outcomes.
 *
 * <h2>Checked twice, deliberately</h2>
 *
 * <p>Once at fan-out, so a suppressed delivery never enters the queue at all, and again immediately
 * before dispatch. The second check is not redundant: a delivery can sit in the queue for hours
 * behind a backoff, and a recipient who unsubscribes in that window must not receive the message that
 * was already enqueued. The pre-dispatch check is batched - one query per claimed batch rather than
 * one per delivery - because it is on the hottest path in the service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuppressionService {

    /** How many suppressions the admin listing returns at once. */
    private static final int LIST_LIMIT = 500;

    private final SuppressionRepository repository;

    /** Whether this destination is currently suppressed on this channel. */
    @Transactional(readOnly = true)
    public boolean isSuppressed(ChannelType channel, String address, Instant now) {
        return repository.findActive(kind(channel), Pii.normalizeAddress(address), now).isPresent();
    }

    /**
     * The subset of these destinations that is suppressed, for the pre-dispatch re-check.
     *
     * <p>Addresses are normalized here rather than at the call site so that the set returned is
     * keyed the same way the caller will look it up - a caller comparing raw addresses against
     * normalized results would silently find nothing and send to every suppressed address in the
     * batch.
     */
    @Transactional(readOnly = true)
    public Set<String> suppressedAmong(ChannelType channel, Collection<String> addresses, Instant now) {
        Set<String> normalized = addresses.stream()
                .map(Pii::normalizeAddress)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return repository.findActiveAddresses(kind(channel), normalized, now);
    }

    /**
     * Adds or refreshes a suppression.
     *
     * <p>Idempotent by intent: a provider that reports the same bounce twice, which they do, updates
     * the existing entry rather than creating a second one. The expiry is overwritten rather than
     * merged because the newest report is the better information - an address that soft-bounced and
     * then hard-bounced should end up permanently suppressed, not suppressed for a day.
     */
    @Transactional
    public SuppressionView suppress(ChannelType channel, String address, String reason, String detail,
                                    Instant expiresAt) {
        String normalized = Pii.normalizeAddress(address);
        SuppressionEntity entity = repository
                .findActive(kind(channel), normalized, Instant.now())
                .orElseGet(() -> SuppressionEntity.builder()
                        .channel(kind(channel))
                        .address(normalized)
                        .build());
        entity.setReason(reason);
        entity.setDetail(detail);
        entity.setExpiresAt(expiresAt);
        SuppressionEntity saved = repository.saveAndFlush(entity);

        log.info("Suppressed {} on {} ({})", Pii.address(normalized), channel, reason);
        return toView(saved);
    }

    /** Removes a suppression - a support action, taken when a recipient says the bounce was wrong. */
    @Transactional
    public boolean release(ChannelType channel, String address) {
        return repository.findActive(kind(channel), Pii.normalizeAddress(address), Instant.now())
                .map(entity -> {
                    repository.delete(entity);
                    log.info("Released suppression of {} on {}", Pii.address(address), channel);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<SuppressionView> list(ChannelType channel) {
        return repository.listActive(kind(channel), Instant.now(), LIST_LIMIT).stream()
                .map(SuppressionService::toView)
                .toList();
    }

    private static SuppressionView toView(SuppressionEntity entity) {
        return new SuppressionView(
                entity.getId(),
                ChannelType.valueOf(entity.getChannel().name()),
                entity.getAddress(),
                entity.getReason(),
                entity.getDetail(),
                entity.getCreatedAt(),
                entity.getExpiresAt());
    }

    private static ChannelKind kind(ChannelType channel) {
        return ChannelKind.valueOf(channel.name());
    }
}
