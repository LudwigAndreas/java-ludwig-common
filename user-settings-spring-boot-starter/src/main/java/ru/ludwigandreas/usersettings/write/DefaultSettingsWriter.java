package ru.ludwigandreas.usersettings.write;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.exception.RejectedSetting;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingUpdate;
import ru.ludwigandreas.usersettings.exception.SettingViolation;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.audit.SettingsAuditRecorder;
import ru.ludwigandreas.usersettings.cache.AfterCommitEviction;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.audit.SettingAuditAction;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;
import ru.ludwigandreas.usersettings.exception.SettingConversionException;
import ru.ludwigandreas.usersettings.exception.SettingNotEditableException;
import ru.ludwigandreas.usersettings.exception.SettingValidationException;
import ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;
import ru.ludwigandreas.usersettings.resolve.SettingsAccess;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;
import ru.ludwigandreas.usersettings.resolve.SettingsResolutionEngine;
import ru.ludwigandreas.usersettings.resolve.SettingsSubjects;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;

/**
 * Owner-mode writes: validated, audited, published and evicted, in one transaction.
 *
 * <h2>Order of operations, and why</h2>
 *
 * <p>Validate everything, then write, then audit, then publish, then evict after commit. The first
 * two are separated so that a bulk update cannot half-apply: a settings form that saves three fields
 * and rejects the fourth must leave the user looking at a screen that matches the stored state, and
 * validating as it goes would leave three written and one not.
 *
 * <p>The audit row and the outbox event go into the same transaction as the change itself. Both
 * would be simpler to write afterwards and both would then be able to disagree with the data - an
 * audit trail recording a change that rolled back, a projection told about one that never happened.
 *
 * <p>The eviction is the only thing that happens outside, and it happens after commit. See
 * {@link AfterCommitEviction} for the window that closes.
 *
 * <h2>Concurrent writes to the same setting</h2>
 *
 * <p>Two callers setting the same setting for the same subject at the same instant both find no row
 * and both insert; one loses on the unique constraint and gets a
 * {@code DataIntegrityViolationException}. That is deliberately left to propagate rather than
 * retried here: the loser's value would otherwise silently overwrite the winner's with no
 * last-writer-wins rule anybody chose, and a settings API that answers "try again" to a genuine race
 * is more honest than one that picks for you.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultSettingsWriter implements SettingsWriter {

    private final UserSettingValueRepository repository;
    private final SettingDefinitionRegistry registry;
    private final SettingsResolutionEngine engine;
    private final SettingsCache cache;
    private final SettingsAccessPolicy accessPolicy;
    private final SettingsTenantResolver tenantResolver;
    private final SettingsAuditRecorder audit;
    private final SettingsEventPublisher events;
    private final SettingsMetrics metrics;
    private final Clock clock;

    @Override
    @Transactional
    public <T> ResolvedValue<T> set(PrincipalRef ref, SettingDefinition<T> definition, T value) {
        SettingsSubject subject = subjectFor(ref);
        SettingsAccess access = accessPolicy.check(subject);
        requireEditable(definition, access);
        registry.require(definition);

        List<RejectedSetting> rejected = validate(definition, value);
        if (!rejected.isEmpty()) {
            throw new SettingValidationException(rejected);
        }

        SettingScope scope = SettingScope.user(subject.subject());
        apply(subject, scope, definition, value);
        AfterCommitEviction.evict(cache, subject);

        // Resolved rather than echoed back: writing the same value the tenant already supplies still
        // creates a user-layer row, and the caller needs to see that it now resolves from USER.
        return engine.resolve(subject).resolved(definition);
    }

    @Override
    @Transactional
    public void reset(PrincipalRef ref, SettingDefinition<?> definition) {
        SettingsSubject subject = subjectFor(ref);
        SettingsAccess access = accessPolicy.check(subject);
        requireEditable(definition, access);
        registry.require(definition);

        apply(subject, SettingScope.user(subject.subject()), definition, null);
        AfterCommitEviction.evict(cache, subject);
    }

    @Override
    @Transactional
    public ResolvedSettings setAll(PrincipalRef ref, List<SettingUpdate<?>> updates) {
        SettingsSubject subject = subjectFor(ref);
        SettingsAccess access = accessPolicy.check(subject);

        // Every update is checked before any is written, so the transaction either applies the whole
        // form or none of it. Collecting the rejections rather than throwing on the first also means
        // a form with three bad fields comes back naming all three.
        List<RejectedSetting> rejected = new ArrayList<>();
        for (SettingUpdate<?> update : updates) {
            requireEditable(update.definition(), access);
            registry.require(update.definition());
            rejected.addAll(validateUpdate(update));
        }
        if (!rejected.isEmpty()) {
            throw new SettingValidationException(rejected);
        }

        SettingScope scope = SettingScope.user(subject.subject());
        for (SettingUpdate<?> update : updates) {
            applyUpdate(subject, scope, update);
        }
        AfterCommitEviction.evict(cache, subject);
        return engine.resolve(subject);
    }

    @Override
    @Transactional
    public <T> void setForScope(SettingsSubject subject, SettingScope scope,
                                SettingDefinition<T> definition, T value) {
        accessPolicy.requireAdmin(subject);
        requireWritableScope(subject, scope);
        registry.require(definition);

        List<RejectedSetting> rejected = validate(definition, value);
        if (!rejected.isEmpty()) {
            throw new SettingValidationException(rejected);
        }
        apply(subject, scope, definition, value);
        evictForScope(subject, scope);
    }

    @Override
    @Transactional
    public void resetForScope(SettingsSubject subject, SettingScope scope, SettingDefinition<?> definition) {
        accessPolicy.requireAdmin(subject);
        requireWritableScope(subject, scope);
        registry.require(definition);

        apply(subject, scope, definition, null);
        evictForScope(subject, scope);
    }

    /** Captures the wildcard so the converter and the value agree on a type. */
    private <T> void applyUpdate(SettingsSubject subject, SettingScope scope, SettingUpdate<T> update) {
        apply(subject, scope, update.definition(), update.value());
    }

    /**
     * The single write path: upsert or delete, audit, publish.
     *
     * <p>A {@code null} value means delete. Representing "no value" as the absence of a row rather
     * than as a row holding null is what keeps "explicitly cleared" and "never set" from becoming
     * the same state - they resolve identically, but only one of them should stop a tenant default
     * from being inherited, and neither should.
     */
    private <T> void apply(SettingsSubject subject, SettingScope scope,
                           SettingDefinition<T> definition, T value) {
        Optional<UserSettingValueEntity> existing =
                repository.findValue(subject.tenantId(), scope, definition.getKey());
        // A tombstone holds no value, so "what it was before" is genuinely nothing - not the value it
        // held before it was reset, which the audit trail already recorded when that happened.
        String previous = existing.filter(row -> !row.isRemoved())
                .map(UserSettingValueEntity::getValueText)
                .orElse(null);
        Instant now = clock.instant();

        if (value == null) {
            if (existing.isEmpty() || existing.get().isRemoved()) {
                // Resetting something that was never set, or resetting it twice. A no-op rather than
                // an error: it is what a user pressing "reset" on an inherited value is asking for,
                // and it has already happened. Publishing an event here would also republish a
                // removal every time the button was pressed.
                return;
            }
            // A tombstone rather than a delete, so that the removal has a timestamp a projection can
            // compare a late "set" event against. See UserSettingValueEntity#isRemoved.
            UserSettingValueEntity tombstone = existing.get();
            tombstone.setRemoved(true);
            tombstone.setValueText(null);
            tombstone.setValueType(null);
            tombstone.setChangedAt(now);
            repository.save(tombstone);

            audit.recordChange(subject, definition, scope, previous, null, SettingAuditAction.RESET);
            metrics.recordReset(definition.getCategory(), scope.layer());
            events.publishSettingChanged(changeEvent(subject, scope, definition, null, null, true, now));
            return;
        }

        SettingValueConverter<T> converter = registry.converterFor(definition);
        String encoded;
        try {
            encoded = converter.toStorage(value);
        } catch (RuntimeException e) {
            throw new SettingConversionException(definition.getKey(), definition.getType().getSimpleName(), e);
        }

        // Reuses the row when one exists, tombstone included: setting a value that was previously
        // reset revives that row rather than inserting a second one against the unique constraint.
        UserSettingValueEntity entity = existing.orElseGet(UserSettingValueEntity::new);
        entity.setRemoved(false);
        entity.setTenantId(subject.tenantId());
        entity.setScopeType(scope.layer());
        entity.setScopeId(scope.scopeId());
        entity.setSettingKey(definition.getKey());
        entity.setValueText(encoded);
        entity.setValueType(converter.typeId());
        entity.setChangedAt(now);
        repository.save(entity);

        audit.recordChange(subject, definition, scope, previous, encoded, SettingAuditAction.SET);
        metrics.recordWrite(definition.getCategory(), scope.layer());
        events.publishSettingChanged(
                changeEvent(subject, scope, definition, encoded, converter.typeId(), false, now));
    }

    private UserSettingChangedEvent changeEvent(SettingsSubject subject, SettingScope scope,
                                                SettingDefinition<?> definition, String encoded,
                                                String typeId, boolean removed, Instant occurredAt) {
        return new UserSettingChangedEvent(
                UUID.randomUUID().toString(),
                subject.tenantId(),
                scope.layer(),
                scope.scopeId(),
                definition.getKey(),
                encoded,
                typeId,
                removed,
                occurredAt);
    }

    private <T> List<RejectedSetting> validate(SettingDefinition<T> definition, T value) {
        Optional<SettingViolation> violation = definition.validate(value);
        return violation
                .map(found -> List.of(new RejectedSetting(definition.getKey(), found)))
                .orElseGet(List::of);
    }

    private <T> List<RejectedSetting> validateUpdate(SettingUpdate<T> update) {
        return validate(update.definition(), update.value());
    }

    /**
     * The {@code userEditable} flag governs the subject editing their own value, and nothing else. An
     * administrator managing a locked setting is the reason the flag exists rather than a hole in it:
     * "the user may not change this" and "nobody may change this" are different statements, and only
     * the first one is useful.
     */
    private void requireEditable(SettingDefinition<?> definition, SettingsAccess access) {
        if (access != SettingsAccess.ADMIN && !definition.isUserEditable()) {
            throw new SettingNotEditableException(definition.getKey());
        }
    }

    private static void requireWritableScope(SettingsSubject subject, SettingScope scope) {
        if (scope.layer() == SettingLayer.PLATFORM || scope.layer() == SettingLayer.DEFAULT) {
            throw new IllegalArgumentException(
                    "The " + scope.layer() + " layer is not stored and cannot be written: platform"
                            + " defaults come from configuration so they can be hot-reloaded, and the"
                            + " default is declared in the SettingDefinition itself");
        }
        // A tenant-scoped write names the tenant twice - once as the scope, once as the tenant the
        // caller is confined to - and the two have to agree. They cannot disagree harmlessly: the row
        // would be filed under the caller's tenant with another tenant's id as its scope, where no
        // resolution would ever read it and no screen would ever show it.
        if (scope.layer() == SettingLayer.TENANT && !scope.scopeId().equals(subject.tenantId())) {
            throw new SettingsAccessDeniedException();
        }
    }

    /**
     * A user-scoped write affects exactly one cache entry; a role- or tenant-scoped one affects
     * everybody who inherits from it, and the writer cannot enumerate them without querying the
     * directory.
     */
    private void evictForScope(SettingsSubject subject, SettingScope scope) {
        if (scope.layer() == SettingLayer.USER && scope.scopeId().equals(subject.subject())) {
            AfterCommitEviction.evict(cache, subject);
        } else {
            AfterCommitEviction.evictAll(cache);
        }
    }

    private SettingsSubject subjectFor(PrincipalRef ref) {
        return SettingsSubjects.require(tenantResolver, ref);
    }
}
