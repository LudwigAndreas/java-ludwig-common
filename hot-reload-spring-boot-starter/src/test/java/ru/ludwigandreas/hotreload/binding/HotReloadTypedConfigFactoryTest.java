package ru.ludwigandreas.hotreload.binding;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.hotreload.core.ReloadableSource;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HotReloadTypedConfigFactoryTest {

    @Test
    void refreshesOnlyWhenAChangedKeyTouchesTheConfiguredPrefix() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.name", "v1");
        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        HotReloadTypedConfigFactory factory = new HotReloadTypedConfigFactory(
                environment, new ConfigurationBinder(null), coordinator);

        RefreshableConfig<SampleConfig> config = factory.create("app", SampleConfig.class);
        AtomicInteger refreshCount = new AtomicInteger();
        config.addListener(c -> refreshCount.incrementAndGet());

        coordinator.reload(fixedSource("unrelated", Map.of("other.key", "x")));
        assertThat(refreshCount).hasValue(0);

        environment.setProperty("app.name", "v2");
        coordinator.reload(fixedSource("config-file", Map.of("app.name", "v2")));

        assertThat(refreshCount).hasValue(1);
        assertThat(config.get().getName()).isEqualTo("v2");
    }

    private static ReloadableSource fixedSource(String id, Map<String, Object> content) {
        return new ReloadableSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Map<String, Object> load() {
                return content;
            }
        };
    }
}
