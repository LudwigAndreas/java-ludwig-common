package ru.ludwigandreas.usersettings.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.exception.RejectedSetting;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingUpdate;
import ru.ludwigandreas.usersettings.exception.SettingViolation;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.exception.ReadOnlySettingsException;
import ru.ludwigandreas.usersettings.exception.SettingProblemCodes;
import ru.ludwigandreas.usersettings.exception.SettingValidationException;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;

/**
 * Writes settings whose values arrive as text rather than as typed objects.
 *
 * <p>This is the bridge a generic API needs. A REST endpoint that accepts "here is a map of setting
 * key to value" cannot know the types at compile time - that is the whole point of the keys being
 * data at the transport boundary and code everywhere else - so something has to look each key up,
 * find its definition, and decode the text with that definition's own converter. Doing it here
 * rather than in the controller means the shipped controllers and a service's own controllers behave
 * identically, including how they fail.
 *
 * <p>A value that will not decode is reported as a validation failure rather than as a server error:
 * from the caller's side, sending {@code "Mars/Olympus"} for a timezone is a bad request, and the
 * fact that the failure surfaced inside a converter is this service's business.
 */
@RequiredArgsConstructor
public class RawSettingWriter {

    /**
     * Null in projection mode, where no {@code SettingsWriter} bean exists at all.
     *
     * <p>Nullable here rather than a second no-op implementation of {@code SettingsWriter}, because
     * the distinction is worth keeping at the bean level: business code that injects a
     * {@code SettingsWriter} should fail to start in projection mode rather than fail on the first
     * user who tries to save something. This class is the one place that has to cope with both,
     * because the shipped controller is mounted in both modes and has to answer an HTTP request
     * either way.
     */
    private final SettingsWriter writer;
    private final SettingDefinitionRegistry registry;

    /** Whether this service owns the settings it serves. */
    public boolean isWritable() {
        return writer != null;
    }

    /**
     * Applies a map of encoded values in one transaction.
     *
     * <p>A null value resets the setting, matching {@link SettingUpdate#reset}. Every value is
     * decoded before any is written, so a map with one undecodable entry rejects the whole request -
     * the same all-or-nothing rule {@link SettingsWriter#setAll} applies to validation.
     */
    public ResolvedSettings setAll(PrincipalRef ref, Map<String, String> encodedValues) {
        List<SettingUpdate<?>> updates = new ArrayList<>();
        List<RejectedSetting> rejected = new ArrayList<>();
        encodedValues.forEach((key, raw) -> {
            SettingDefinition<?> definition = registry.require(key);
            try {
                updates.add(decode(definition, raw));
            } catch (RuntimeException e) {
                // The offending text is not echoed back; see SettingValidationException.
                rejected.add(new RejectedSetting(key,
                        SettingViolation.of(SettingProblemCodes.VALIDATION_PATTERN)));
            }
        });
        if (!rejected.isEmpty()) {
            throw new SettingValidationException(rejected);
        }
        return requireWriter().setAll(ref, updates);
    }

    /** Clears the subject's own values for these settings. */
    public ResolvedSettings resetAll(PrincipalRef ref, List<String> keys) {
        List<SettingUpdate<?>> updates = new ArrayList<>();
        for (String key : keys) {
            updates.add(SettingUpdate.reset(registry.require(key)));
        }
        return requireWriter().setAll(ref, updates);
    }

    private SettingsWriter requireWriter() {
        if (writer == null) {
            throw new ReadOnlySettingsException();
        }
        return writer;
    }

    /** Captures the definition's type so the converter and the update agree on it. */
    private <T> SettingUpdate<T> decode(SettingDefinition<T> definition, String raw) {
        if (raw == null) {
            return SettingUpdate.reset(definition);
        }
        SettingValueConverter<T> converter = registry.converterFor(definition);
        return SettingUpdate.of(definition, converter.fromStorage(raw));
    }

    /** The encoded form of a resolved value, for rendering it back out through the same vocabulary. */
    public <T> String encode(SettingDefinition<T> definition, T value) {
        return value == null ? null : registry.converterFor(definition).toStorage(value);
    }
}
