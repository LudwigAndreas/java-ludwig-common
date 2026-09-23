package ru.ludwigandreas.usersettings.resolve;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.usersettings.api.ScopedValue;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingValueSource;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.api.SettingValueTypes;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;

/**
 * Tenant and platform defaults that come from configuration rather than from rows.
 *
 * <p>Two things follow from that choice, and both are the point. A default can be changed without a
 * migration, which matters because changing one is a routine operational act and creating a row per
 * tenant per setting to express "the usual value" is not. And with the hot-reload module on the
 * classpath it can be changed without a redeploy, which is what turns "we need to switch the default
 * digest to weekly" from a release into an edit.
 *
 * <p>The discriminator is taken from the definition's own converter rather than stored alongside the
 * configured text: an operator writing {@code Europe/Moscow} in a YAML file should not also have to
 * write {@code zone-id} next to it, and the definition already knows.
 */
@RequiredArgsConstructor
public class ConfiguredSettingValueSource implements SettingValueSource {

    private final SettingDefaultsProvider defaults;
    private final SettingDefinitionRegistry registry;

    /**
     * Configured values carry no timestamp of their own. {@link Instant#EPOCH} is used rather than
     * "now", because "now" would make a configured default look newer than every stored value on
     * every single read - and the timestamp is what a projection compares to decide whether an
     * incoming change is stale.
     */
    private static final Instant CONFIGURED_AT = Instant.EPOCH;

    @Override
    public List<ScopedValue> load(SettingsSubject subject, List<SettingScope> scopes) {
        List<ScopedValue> values = new ArrayList<>();
        for (SettingScope scope : scopes) {
            if (scope.layer() == SettingLayer.TENANT) {
                // Confined to the subject's own tenant, not to whatever tenant a scope resolver named.
                // The database source is scoped by the subject's tenant and so cannot be talked into
                // another one; without this check a custom resolver contributing a foreign tenant scope
                // could read that tenant's configured defaults, which is the same leak one level up.
                if (scope.scopeId().equals(subject.tenantId())) {
                    collect(values, scope, defaults.tenantDefaults(scope.scopeId()));
                }
            } else if (scope.layer() == SettingLayer.PLATFORM) {
                collect(values, scope, defaults.platformDefaults());
            }
        }
        return values;
    }

    /**
     * A configured key that no definition declares is skipped rather than fatal. Configuration
     * outlives deployments: a default for a setting a later release removed would otherwise stop
     * every service that still has the old YAML mounted, which is a bad way to find out that a
     * setting was retired.
     */
    private void collect(List<ScopedValue> values, SettingScope scope, Map<String, String> configured) {
        configured.forEach((key, raw) -> registry.converterForKey(key).ifPresent(converter ->
                values.add(new ScopedValue(scope, key, raw, typeIdOf(converter.typeId()), CONFIGURED_AT))));
    }

    private static String typeIdOf(String typeId) {
        return typeId == null ? SettingValueTypes.STRING : typeId;
    }
}
