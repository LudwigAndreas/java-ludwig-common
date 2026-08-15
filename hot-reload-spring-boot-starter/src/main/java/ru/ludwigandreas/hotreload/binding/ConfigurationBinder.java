package ru.ludwigandreas.hotreload.binding;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Set;

/**
 * Binds a flat property namespace to a typed object via Spring's own {@link Binder} (the exact machinery
 * {@code @ConfigurationProperties} uses), then validates it with jakarta.validation - so a hot-reloaded
 * value that fails {@code @NotBlank}/{@code @Min}/... constraints is rejected before it ever reaches
 * application code, rather than propagating a half-valid config.
 */
public class ConfigurationBinder {

    private final Validator validator;

    /** @param validator may be {@code null} to skip validation entirely (binding only) */
    public ConfigurationBinder(Validator validator) {
        this.validator = validator;
    }

    public <T> T bindAndValidate(Environment environment, String prefix, Class<T> type) {
        T bound = Binder.get(environment).bindOrCreate(prefix, type);
        validate(prefix, type, bound);
        return bound;
    }

    private <T> void validate(String prefix, Class<T> type, T bound) {
        if (validator == null) {
            return;
        }
        Set<ConstraintViolation<T>> violations = validator.validate(bound);
        if (!violations.isEmpty()) {
            List<String> messages = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .toList();
            throw new ConfigValidationException(prefix, type, messages);
        }
    }
}
