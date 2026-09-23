package ru.ludwigandreas.usersettings.audit;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.entity.SettingAuditAction;
import ru.ludwigandreas.usersettings.entity.UserSettingAuditEntity;
import ru.ludwigandreas.usersettings.repository.UserSettingAuditRepository;

/**
 * Writes the change trail.
 *
 * <p>Runs inside the caller's transaction on the write path, so an audit row and the change it
 * describes commit together. That is the property worth having: an audit trail written in a separate
 * transaction is one that can record a change that was rolled back, or miss one that was not, and
 * either makes the whole trail something an auditor has to qualify rather than rely on.
 *
 * <p>Administrative reads are the exception and are written outside any transaction of their own, by
 * the repository's. A read has nothing to be atomic with.
 */
@Slf4j
@RequiredArgsConstructor
public class SettingsAuditRecorder {

    private static final String SYSTEM_ACTOR = "system";

    private final UserSettingAuditRepository repository;
    private final SettingsCorrelationIdProvider correlation;

    /** A value was set or reset. {@code oldValue}/{@code newValue} are the encoded forms. */
    public void recordChange(SettingsSubject subject, SettingDefinition<?> definition, SettingScope scope,
                             String oldValue, String newValue, SettingAuditAction action) {
        UserSettingAuditEntity entry = base(subject, action);
        entry.setSettingKey(definition.getKey());
        entry.setCategory(definition.getCategory());
        entry.setScopeType(scope.layer());
        entry.setScopeId(scope.scopeId());
        entry.setOldValue(SettingsRedaction.forAudit(definition, oldValue));
        entry.setNewValue(SettingsRedaction.forAudit(definition, newValue));
        entry.setRedacted(definition.isPii());
        repository.save(entry);
    }

    /**
     * An administrator read somebody else's settings.
     *
     * <p>One row for the whole read rather than one per setting: the interesting fact is that this
     * administrator looked at this subject at this time, and a row per setting would bury it under
     * however many settings the service happens to declare.
     */
    public void recordAdminRead(SettingsSubject subject) {
        repository.save(base(subject, SettingAuditAction.ADMIN_READ));
    }

    private UserSettingAuditEntity base(SettingsSubject subject, SettingAuditAction action) {
        // The id is left for Hibernate to generate. GeneratedEntity decides "is this new" by the id
        // being null, so assigning one here would turn every insert into a select-then-update.
        UserSettingAuditEntity entry = new UserSettingAuditEntity();
        entry.setTenantId(subject.tenantId());
        entry.setSubject(subject.subject());
        entry.setActor(SecurityPrincipals.currentSubject().orElse(SYSTEM_ACTOR));
        entry.setAction(action);
        entry.setOccurredAt(Instant.now());
        entry.setRedacted(false);
        correlation.currentCorrelationId().ifPresent(entry::setCorrelationId);
        return entry;
    }
}
