package ru.ludwigandreas.usersettings.audit;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * Writes the change trail.
 *
 * <p>A caller of the platform's {@link AuditSink} rather than an SPI of its own, which it never was - it was
 * a concrete class writing {@code user_setting_audit} directly, the only one of the nine audit mechanisms
 * with no logging path at all. Those rows migrated into {@code audit_event} by the {@code audit-002}
 * changeset, and their marker was rewritten by {@code audit-004}.
 *
 * <p>Runs inside the caller's transaction on the write path, so an audit row and the change it describes
 * commit together. That is the property worth having, and it is now also the reason {@code settings} is the
 * one category whose {@code AuditFailurePolicy} defaults to {@code FAIL_OPERATION}: an audit trail written
 * in a separate transaction is one that can record a change that was rolled back, or miss one that was not,
 * and either makes the whole trail something an auditor has to qualify rather than rely on. {@code JpaAuditSink}
 * is {@code Propagation.SUPPORTS} precisely so that this still holds.
 *
 * <p>Administrative reads are the exception and are written outside any transaction of their own. A read has
 * nothing to be atomic with.
 */
@RequiredArgsConstructor
public class SettingsAuditRecorder {

    /** The resource type every settings event is about. */
    public static final String RESOURCE_TYPE = "setting";

    private final AuditSink auditSink;
    private final ActorResolver actors;
    private final SettingsCorrelationIdProvider correlation;
    private final Clock clock;

    /**
     * A value was set or reset. {@code oldValue}/{@code newValue} are the encoded forms.
     *
     * @param subject    whose setting changed
     * @param definition which setting
     * @param scope      the layer written at
     * @param oldValue   the previous encoded value, or {@code null}
     * @param newValue   the new encoded value, or {@code null} for a reset
     * @param action     which kind of write
     */
    public void recordChange(SettingsSubject subject, SettingDefinition<?> definition, SettingScope scope,
                             String oldValue, String newValue, SettingAuditAction action) {
        Map<String, Object> attributes = base(subject);
        attributes.put("settingKey", definition.getKey());
        attributes.put("settingCategory", definition.getCategory());
        attributes.put("scopeType", scope.layer() == null ? null : scope.layer().name());
        attributes.put("scopeId", scope.scopeId());
        // Redacted here, at the point the entry is built, never at the sink - see Redactor. The
        // classification is declarative: the definition says whether the value is personal data, which is
        // the one thing no name heuristic and no configured list can know.
        attributes.put("oldValue", forAudit(definition, oldValue));
        attributes.put("newValue", forAudit(definition, newValue));
        attributes.put("redacted", definition.isPii());
        auditSink.record(event(subject, action, definition.getKey(), attributes));
    }

    /**
     * An administrator read somebody else's settings.
     *
     * <p>One event for the whole read rather than one per setting: the interesting fact is that this
     * administrator looked at this subject at this time, and an event per setting would bury it under
     * however many settings the service happens to declare.
     *
     * @param subject whose settings were read
     */
    public void recordAdminRead(SettingsSubject subject) {
        auditSink.record(event(subject, SettingAuditAction.ADMIN_READ, null, base(subject)));
    }

    /**
     * The value as the audit trail should hold it.
     *
     * <p>What was {@code SettingsRedaction.forAudit}. The marker itself and the argument for it - a fixed
     * string rather than a hash or a truncation, and deliberately not configurable - moved to
     * {@link Redaction}, which is where the whole platform now reads it from.
     */
    private String forAudit(SettingDefinition<?> definition, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        return definition.isPii() ? Redaction.MASK : rawValue;
    }

    private AuditEvent event(SettingsSubject subject, SettingAuditAction action, String settingKey,
                             Map<String, Object> attributes) {
        return AuditEvent.builder()
                .category(AuditCategories.SETTINGS)
                .action(action.action())
                .occurredAt(clock.instant())
                // The actor is who did it and onBehalfOf is whose setting it was - the direction the old
                // table's `actor` and `subject` columns actually meant, and the one an administrator
                // editing somebody else's profile makes visible. Resolved through the platform's one
                // ActorResolver, so this string and db-core's created_by cannot disagree.
                .actor(actors.currentActorOrSystem().onBehalfOf(subject.subject()))
                .resource(new Resource(RESOURCE_TYPE, settingKey, null))
                .correlationId(correlation.currentCorrelationId().orElse(null))
                .attributes(attributes)
                .build();
    }

    private Map<String, Object> base(SettingsSubject subject) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tenantId", subject.tenantId());
        attributes.put("subject", subject.subject());
        return attributes;
    }
}
