package ru.ludwigandreas.hotreload.propertysource;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReloadablePropertySourceTest {

    @Test
    void startsEmptyByDefault() {
        ReloadablePropertySource source = new ReloadablePropertySource("test");

        assertThat(source.getPropertyNames()).isEmpty();
        assertThat(source.getProperty("missing")).isNull();
        assertThat(source.containsProperty("missing")).isFalse();
    }

    @Test
    void updateReplacesContentAtomically() {
        ReloadablePropertySource source = new ReloadablePropertySource("test", Map.of("a", "1"));

        assertThat(source.getProperty("a")).isEqualTo("1");

        source.update(Map.of("b", "2"));

        assertThat(source.getProperty("a")).isNull();
        assertThat(source.getProperty("b")).isEqualTo("2");
        assertThat(source.getPropertyNames()).containsExactly("b");
    }

    @Test
    void getSourceReflectsCurrentContent() {
        ReloadablePropertySource source = new ReloadablePropertySource("test");
        source.update(Map.of("x", "y"));

        assertThat(source.getSource()).containsExactly(Map.entry("x", "y"));
    }
}
