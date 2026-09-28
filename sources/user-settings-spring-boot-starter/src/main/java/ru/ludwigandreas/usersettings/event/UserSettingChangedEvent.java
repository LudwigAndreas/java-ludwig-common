package ru.ludwigandreas.usersettings.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import ru.ludwigandreas.usersettings.api.SettingLayer;

/**
 * One setting value changed at the owner.
 *
 * <p>Carries the complete state of that one row, not a delta: the scope it belongs to, the encoded
 * value and the encoding. Applying the same event twice therefore produces the same row, which is
 * what makes projection idempotent without a de-duplication table.
 *
 * <p>Tolerant of unknown fields, like every event this platform consumes. The owner will add to this
 * schema, and a consumer that failed on a new field would turn the owner's next release into an
 * outage in every service that projects it.
 *
 * <p>The value is present even for a PII-flagged setting. Redaction governs the audit trail, the
 * logs, the metrics and the problem documents - not the replication of the data itself, which is the
 * whole point of the projection. A deployment that does not want a setting's value leaving the owner
 * should not project that setting's service, rather than relying on the event to be lossy.
 *
 * @param eventId    the owner's id for this change, for logging and de-duplication diagnostics
 * @param tenantId   the tenant the row belongs to
 * @param scopeType  which layer the row lives at
 * @param scopeId    the id within that layer, and the aggregate the event is about: the subject for
 *                   a user-scoped change, the role code for a role-scoped one, the tenant id for a
 *                   tenant-scoped one. There is deliberately no separate "subject" field - a
 *                   tenant-wide change has no single subject, and a field that had to hold the
 *                   administrator who made it would be read as the person it was about
 * @param settingKey which setting
 * @param valueText  the encoded value; null when the value was removed
 * @param valueType  how {@code valueText} was encoded; null when the value was removed
 * @param removed    true when the row was deleted rather than written
 * @param occurredAt when the change happened at the owner - the ordering key a projection compares,
 *                   because Kafka orders only within a partition and a subject's events can be
 *                   repartitioned when the topic is scaled
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSettingChangedEvent(
        String eventId,
        String tenantId,
        SettingLayer scopeType,
        String scopeId,
        String settingKey,
        String valueText,
        String valueType,
        boolean removed,
        Instant occurredAt) {
}
