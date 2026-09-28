package ru.ludwigandreas.hotreload.binding;

import ru.ludwigandreas.hotreload.core.HotReloadException;

import java.util.List;

/** A bound {@code @ConfigurationProperties}-style object failed jakarta.validation constraints. */
public class ConfigValidationException extends HotReloadException {

    private final List<String> violations;

    public ConfigValidationException(String prefix, Class<?> type, List<String> violations) {
        super("Validation failed for configuration '" + prefix + "' (" + type.getName() + "): " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
