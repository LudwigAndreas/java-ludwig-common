package ru.ludwigandreas.hotreload.binding;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationBinderTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void bindsAndValidatesSuccessfully() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.name", "demo");
        environment.setProperty("app.retries", "3");

        SampleConfig config = new ConfigurationBinder(validator).bindAndValidate(environment, "app", SampleConfig.class);

        assertThat(config.getName()).isEqualTo("demo");
        assertThat(config.getRetries()).isEqualTo(3);
    }

    @Test
    void throwsConfigValidationExceptionWhenConstraintsAreViolated() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.retries", "0");

        ConfigurationBinder binder = new ConfigurationBinder(validator);

        assertThatThrownBy(() -> binder.bindAndValidate(environment, "app", SampleConfig.class))
                .isInstanceOf(ConfigValidationException.class)
                .satisfies(e -> assertThat(((ConfigValidationException) e).getViolations()).isNotEmpty());
    }

    @Test
    void skipsValidationWhenNoValidatorIsProvided() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.retries", "0");

        SampleConfig config = new ConfigurationBinder(null).bindAndValidate(environment, "app", SampleConfig.class);

        assertThat(config.getRetries()).isZero();
    }

    @Test
    void bindOrCreateFallsBackToDefaultsWhenPrefixIsUnbound() {
        MockEnvironment environment = new MockEnvironment();

        SampleConfig config = new ConfigurationBinder(null).bindAndValidate(environment, "app", SampleConfig.class);

        assertThat(config.getRetries()).isEqualTo(1);
        assertThat(config.getName()).isNull();
    }
}
