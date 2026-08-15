package ru.ludwigandreas.hotreload.propertysource;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.event.ConfigurationRefreshedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentPropertySourceBridgeTest {

    @Test
    void createsThePropertySourceOnFirstChangeAndUpdatesItOnTheNext() {
        MockEnvironment environment = new MockEnvironment();
        List<Object> published = new ArrayList<>();
        EnvironmentPropertySourceBridge bridge = new EnvironmentPropertySourceBridge(environment, published::add);

        String name = HotReloadPropertySourceNames.forSourceId("file:/etc/app.properties");
        assertThat(environment.getPropertySources().contains(name)).isFalse();

        bridge.onChange(new SourceChangeEvent("file:/etc/app.properties", Map.of("a", "1"), Map.of(), Set.of("a")));

        assertThat(environment.getProperty("a")).isEqualTo("1");
        assertThat(published).hasSize(1);
        ConfigurationRefreshedEvent event = (ConfigurationRefreshedEvent) published.get(0);
        assertThat(event.getSourceId()).isEqualTo("file:/etc/app.properties");
        assertThat(event.getChangedKeys()).containsExactly("a");

        bridge.onChange(new SourceChangeEvent("file:/etc/app.properties", Map.of("a", "2"), Map.of("a", "1"), Set.of("a")));

        assertThat(environment.getProperty("a")).isEqualTo("2");
        assertThat(published).hasSize(2);
    }
}
