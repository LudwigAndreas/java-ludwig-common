package ru.ludwigandreas.usersettings.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.usersettings.exception.UnknownSettingException;

/**
 * Every setting this service declares, resolved for one subject, in one object.
 *
 * <p>This is the type that keeps the module honest about queries. A page that renders twenty
 * settings, or a notification fan-out deciding twenty things about one recipient, asks once and
 * reads twenty times from the result - so the cost is one database round trip per subject rather
 * than twenty, and it stays one when a twenty-first setting is added.
 *
 * <p>Every declared setting has an entry, including the ones nobody has ever set: those resolve to
 * {@link SettingLayer#DEFAULT}. A caller therefore never has to distinguish "absent" from "default",
 * which is a distinction that exists in the storage and has no meaning in the API.
 *
 * <p>Immutable and safe to share. Instances live in the settings cache and are read concurrently by
 * every request for that subject.
 */
public final class ResolvedSettings {

    private final SettingsSubject subject;
    private final Map<String, ResolvedValue<?>> byKey;

    public ResolvedSettings(SettingsSubject subject, Map<String, ResolvedValue<?>> byKey) {
        this.subject = subject;
        this.byKey = Collections.unmodifiableMap(new LinkedHashMap<>(byKey));
    }

    /** Who these settings belong to, and the tenant they were resolved within. */
    public SettingsSubject subject() {
        return subject;
    }

    /**
     * The value, with no indication of where it came from - the common case.
     *
     * @throws UnknownSettingException when the definition was not registered
     */
    public <T> T get(SettingDefinition<T> definition) {
        return resolved(definition).value();
    }

    /**
     * The value together with the layer that supplied it.
     *
     * @throws UnknownSettingException when the definition was not registered
     */
    @SuppressWarnings("unchecked")
    public <T> ResolvedValue<T> resolved(SettingDefinition<T> definition) {
        ResolvedValue<?> value = byKey.get(definition.getKey());
        if (value == null) {
            throw new UnknownSettingException(definition.getKey());
        }
        return (ResolvedValue<T>) value;
    }

    /**
     * Every resolved setting, keyed by setting key.
     *
     * <p>Untyped on purpose: this is for code that renders or exports settings generically - an
     * admin screen, a support dump - and has no definition to type them against. Business code reads
     * through {@link #get(SettingDefinition)} and keeps its types.
     */
    public Map<String, ResolvedValue<?>> entries() {
        return byKey;
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    public int size() {
        return byKey.size();
    }

    @Override
    public String toString() {
        return "ResolvedSettings(subject=" + subject.subject() + ", tenant=" + subject.tenantId()
                + ", settings=" + byKey.size() + ")";
    }
}
