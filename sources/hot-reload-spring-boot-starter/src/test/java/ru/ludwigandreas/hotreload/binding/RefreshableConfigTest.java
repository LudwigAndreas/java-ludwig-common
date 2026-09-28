package ru.ludwigandreas.hotreload.binding;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshableConfigTest {

    @Test
    void refreshUpdatesValueOnValidChange() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.name", "v1");
        RefreshableConfig<SampleConfig> config = new RefreshableConfig<>(
                environment, "app", SampleConfig.class, new ConfigurationBinder(null));

        assertThat(config.get().getName()).isEqualTo("v1");

        environment.setProperty("app.name", "v2");
        boolean refreshed = config.refresh(environment);

        assertThat(refreshed).isTrue();
        assertThat(config.get().getName()).isEqualTo("v2");
    }

    @Test
    void refreshKeepsPreviousValueWhenNewValueFailsValidation() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.name", "v1");
        environment.setProperty("app.retries", "1");
        RefreshableConfig<SampleConfig> config = new RefreshableConfig<>(
                environment, "app", SampleConfig.class, new ConfigurationBinder(defaultValidator()));

        environment.setProperty("app.retries", "0");
        boolean refreshed = config.refresh(environment);

        assertThat(refreshed).isFalse();
        assertThat(config.get().getName()).isEqualTo("v1");
        assertThat(config.get().getRetries()).isEqualTo(1);
    }

    @Test
    void listenersAreNotifiedOnlyOnSuccessfulRefresh() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.name", "v1");
        environment.setProperty("app.retries", "1");
        RefreshableConfig<SampleConfig> config = new RefreshableConfig<>(
                environment, "app", SampleConfig.class, new ConfigurationBinder(defaultValidator()));

        List<String> seen = new ArrayList<>();
        config.addListener(c -> seen.add(c.getName()));

        environment.setProperty("app.retries", "0");
        config.refresh(environment);
        assertThat(seen).isEmpty();

        environment.setProperty("app.retries", "1");
        environment.setProperty("app.name", "v2");
        config.refresh(environment);
        assertThat(seen).containsExactly("v2");
    }

    private static jakarta.validation.Validator defaultValidator() {
        return jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
    }
}
