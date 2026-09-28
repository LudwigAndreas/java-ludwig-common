package ru.ludwigandreas.usersettings.api;

import java.util.List;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.exception.ReadOnlySettingsException;
import ru.ludwigandreas.usersettings.exception.SettingNotEditableException;
import ru.ludwigandreas.usersettings.exception.SettingValidationException;
import ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException;

/**
 * Changing settings. Registered only in owner mode.
 *
 * <p>A projection-mode service has no bean of this type at all, rather than one that throws. The
 * distinction matters at wiring time: a service that accidentally depends on writing settings fails
 * to start in projection mode instead of failing on the first user who tries to save a preference.
 * {@link ReadOnlySettingsException} exists for the shipped REST controllers, which are wired in both
 * modes and have to answer an HTTP request either way.
 *
 * <p>Every method here is transactional, validated and audited, and every one evicts the affected
 * subject from the settings cache <em>after commit</em> - see the eviction note on
 * {@code SettingsCache}. The change event goes to the transactional outbox in the same transaction,
 * so a committed change is always published and a rolled-back one never is.
 */
public interface SettingsWriter {

    /**
     * Sets the subject's own value.
     *
     * @return the value as it now resolves, which is not necessarily what was just written - writing
     *         the same value the tenant already supplies still records a user-layer row, and the
     *         caller should see that it now resolves from {@link SettingLayer#USER}
     * @throws SettingNotEditableException   when the definition is not user-editable and the caller
     *                                       is the subject
     * @throws SettingValidationException    when the value fails the definition's validation
     * @throws SettingsAccessDeniedException when the caller may not write this subject's settings
     */
    <T> ResolvedValue<T> set(PrincipalRef ref, SettingDefinition<T> definition, T value);

    /**
     * Removes the subject's own value, so the layers below supply one again.
     *
     * <p>Not "write the default": storing the default as a user value would pin the setting against
     * a later change to the tenant or platform default, which is the opposite of what someone
     * pressing "reset" is asking for.
     */
    void reset(PrincipalRef ref, SettingDefinition<?> definition);

    /**
     * Applies several changes in one transaction, validating all of them before writing any.
     *
     * <p>All-or-nothing on purpose: a settings form that half-saves leaves the user looking at a
     * screen that matches neither what they submitted nor what is stored.
     *
     * @return the subject's settings as they resolve after the update
     */
    ResolvedSettings setAll(PrincipalRef ref, List<SettingUpdate<?>> updates);

    /**
     * Sets a value at a scope other than the subject's own - a role, a tenant.
     *
     * <p>Requires the administrative authority. The {@code userEditable} flag does not apply: it
     * governs whether a subject may edit their own value, not whether the setting can be managed.
     *
     * @param subject the tenant this write is confined to, and the administrator performing it
     */
    <T> void setForScope(SettingsSubject subject, SettingScope scope, SettingDefinition<T> definition, T value);

    /** Removes a value at a scope other than the subject's own. */
    void resetForScope(SettingsSubject subject, SettingScope scope, SettingDefinition<?> definition);
}
