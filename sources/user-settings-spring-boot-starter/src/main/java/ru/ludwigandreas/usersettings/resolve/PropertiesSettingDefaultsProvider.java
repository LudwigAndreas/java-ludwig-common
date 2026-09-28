package ru.ludwigandreas.usersettings.resolve;

import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

/**
 * Reads the configured defaults through a supplier, so the same class serves both the static and the
 * hot-reloading case.
 *
 * <p>The supplier is called on every resolution rather than being captured once. That is what makes
 * a reload take effect: capturing the maps at construction would pin the defaults to whatever they
 * were when the context started, and the reload would update a copy nobody reads.
 */
@RequiredArgsConstructor
public class PropertiesSettingDefaultsProvider implements SettingDefaultsProvider {

    private final Supplier<Map<String, String>> platform;
    private final Supplier<Map<String, Map<String, String>>> perTenant;

    @Override
    public Map<String, String> platformDefaults() {
        Map<String, String> configured = platform.get();
        return configured == null ? Map.of() : configured;
    }

    @Override
    public Map<String, String> tenantDefaults(String tenantId) {
        Map<String, Map<String, String>> configured = perTenant.get();
        if (configured == null) {
            return Map.of();
        }
        return configured.getOrDefault(tenantId, Map.of());
    }
}
