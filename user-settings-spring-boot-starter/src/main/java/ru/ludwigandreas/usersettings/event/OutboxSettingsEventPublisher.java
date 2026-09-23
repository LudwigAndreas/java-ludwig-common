package ru.ludwigandreas.usersettings.event;

import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.usersettings.api.ConsentDecision;

/**
 * Publishes through the transactional outbox, so a change and its event share one commit.
 *
 * <p>Not a direct Kafka send. A send inside a transaction is the classic dual-write: the broker
 * acknowledges, the transaction then rolls back, and every projection now believes a change that
 * never happened - with no way to find out, because the correction was never published either. The
 * outbox makes the event part of the same commit and lets a separate poller deliver it.
 *
 * <p>The ordering key is the subject, so one subject's changes are delivered in the order they were
 * made. Keying on the setting instead would be finer-grained and wrong: a projection that applied a
 * user's timezone change before their tenant change would resolve a value that never existed at the
 * owner, even though each individual setting was in order.
 */
@RequiredArgsConstructor
public class OutboxSettingsEventPublisher implements SettingsEventPublisher {

    private final OutboxEventPublisher outbox;

    @Override
    public void publishSettingChanged(UserSettingChangedEvent event) {
        outbox.publish(OutboxEvent.builder()
                .aggregateType(SettingsEventTypes.SETTING_AGGREGATE)
                .aggregateId(event.scopeId())
                .eventType(SettingsEventTypes.USER_SETTING_CHANGED)
                .orderingKey(orderingKey(event.tenantId(), event.scopeType() + "/" + event.scopeId()))
                .payload(event)
                .build());
    }

    @Override
    public void publishConsentChanged(ConsentChangedEvent event) {
        outbox.publish(OutboxEvent.builder()
                .aggregateType(SettingsEventTypes.CONSENT_AGGREGATE)
                .aggregateId(event.subject())
                .eventType(event.decision() == ConsentDecision.GRANTED
                        ? SettingsEventTypes.CONSENT_GRANTED
                        : SettingsEventTypes.CONSENT_REVOKED)
                .orderingKey(orderingKey(event.tenantId(), event.subject()))
                // The consent id doubles as the idempotency key: republishing the same decision
                // returns the row the outbox already holds instead of queueing a second copy.
                .idempotencyKey(SettingsEventTypes.CONSENT_AGGREGATE + ":" + event.consentId())
                .payload(event)
                .build());
    }

    /**
     * The same row, minus the idempotency key - see
     * {@link SettingsEventPublisher#republishConsent(ConsentChangedEvent)} for why that key has to
     * go. Everything else is identical, including {@code occurredAt}, which is what lets a projection
     * that is already current recognize the replay and drop it.
     */
    @Override
    public void republishConsent(ConsentChangedEvent event) {
        outbox.publish(OutboxEvent.builder()
                .aggregateType(SettingsEventTypes.CONSENT_AGGREGATE)
                .aggregateId(event.subject())
                .eventType(event.decision() == ConsentDecision.GRANTED
                        ? SettingsEventTypes.CONSENT_GRANTED
                        : SettingsEventTypes.CONSENT_REVOKED)
                .orderingKey(orderingKey(event.tenantId(), event.subject()))
                .payload(event)
                .build());
    }

    /**
     * Tenant-qualified, because two tenants may legitimately use the same subject id - an employee
     * number, a partner code - and sharing an ordering key would serialize one tenant's changes
     * behind another's for no reason.
     */
    private static String orderingKey(String tenantId, String subject) {
        return tenantId + "/" + subject;
    }
}
