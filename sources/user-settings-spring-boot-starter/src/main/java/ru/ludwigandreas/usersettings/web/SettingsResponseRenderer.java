package ru.ludwigandreas.usersettings.web;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.usersettings.consent.ConsentRecord;
import ru.ludwigandreas.usersettings.consent.ConsentState;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.web.dto.ConsentResponse;
import ru.ludwigandreas.usersettings.web.dto.ConsentStateResponse;
import ru.ludwigandreas.usersettings.web.dto.SettingValueResponse;
import ru.ludwigandreas.usersettings.web.dto.SettingsResponse;

/**
 * Resolved settings and consent records to their transport shapes.
 *
 * <p>Hand-written rather than generated, unlike {@code ConsentEntityMapper}. MapStruct maps field to
 * field; this mapping is not one - every value has to go back through its own definition's converter
 * to reach the encoded form the API speaks, and which converter that is depends on the key. A
 * generated mapper would need a hand-written method per setting, which is the thing the whole module
 * exists to avoid.
 *
 * <p>It also applies the one rule that differs between a subject reading their own settings and an
 * administrator reading somebody else's: a PII-flagged value is redacted for the administrator. Not
 * for the subject, obviously - a setting nobody can see is a setting nobody can change - but an
 * administrative screen has no business displaying a person's private values, and "the admin needs
 * to see it to help" is exactly the argument that ends with support staff reading phone numbers out
 * of a console.
 */
@RequiredArgsConstructor
public class SettingsResponseRenderer {

    private final SettingDefinitionRegistry registry;

    /**
     * Every declared setting, rendered.
     *
     * @param redactPii true for an administrative read, where private values are not displayed
     */
    public SettingsResponse toResponse(ResolvedSettings settings, boolean redactPii) {
        List<SettingValueResponse> rendered = new ArrayList<>(settings.size());
        for (SettingDefinition<?> definition : registry.definitions()) {
            rendered.add(render(definition, settings, redactPii));
        }
        return new SettingsResponse(List.copyOf(rendered));
    }

    public ConsentStateResponse toResponse(ConsentState state) {
        return new ConsentStateResponse(state.all().values().stream().map(this::toResponse).toList());
    }

    public ConsentStateResponse toResponse(List<ConsentRecord> history) {
        return new ConsentStateResponse(history.stream().map(this::toResponse).toList());
    }

    /** The decision without its evidence; see {@link ConsentResponse} for why that is left out. */
    public ConsentResponse toResponse(ConsentRecord record) {
        return new ConsentResponse(
                record.consentId(),
                record.consentKey(),
                record.textVersion(),
                record.decision().name(),
                record.locale(),
                record.occurredAt());
    }

    /**
     * Captures the definition's type so the converter, the resolved value and the response all agree
     * on it. The lookup happens inside this method rather than at the call site for that reason: a
     * wildcard definition and a wildcard resolved value passed separately are two unrelated captures
     * as far as the compiler is concerned, however obviously they came from the same setting.
     */
    private <T> SettingValueResponse render(SettingDefinition<T> definition,
                                            ResolvedSettings settings,
                                            boolean redactPii) {
        ResolvedValue<T> resolved = settings.resolved(definition);
        String encoded = encode(definition, resolved.value());
        if (redactPii && definition.isPii() && encoded != null) {
            encoded = Redaction.MASK;
        }
        return new SettingValueResponse(
                definition.getKey(),
                definition.getCategory(),
                encoded,
                resolved.layer().name(),
                resolved.scopeId(),
                definition.isUserEditable(),
                definition.isPii());
    }

    private <T> String encode(SettingDefinition<T> definition, T value) {
        return value == null ? null : registry.converterFor(definition).toStorage(value);
    }
}
