package ru.ludwigandreas.hotreload.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyFileSourceTest {

    @Test
    void loadsFlatPropertiesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "app.name=demo\napp.version=1", StandardCharsets.UTF_8);

        Map<String, Object> content = new PropertyFileSource(file).load();

        assertThat(content).containsEntry("app.name", "demo").containsEntry("app.version", "1");
    }

    @Test
    void loadsNestedYamlFileWithDottedAndIndexedKeys(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.yml");
        Files.writeString(file, """
                app:
                  name: demo
                  servers:
                    - host-a
                    - host-b
                """, StandardCharsets.UTF_8);

        Map<String, Object> content = new PropertyFileSource(file).load();

        assertThat(content)
                .containsEntry("app.name", "demo")
                .containsEntry("app.servers[0]", "host-a")
                .containsEntry("app.servers[1]", "host-b");
    }

    @Test
    void appliesKeyPrefix(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "name=demo", StandardCharsets.UTF_8);

        Map<String, Object> content = new PropertyFileSource(file, "myapp.").load();

        assertThat(content).containsEntry("myapp.name", "demo");
    }

    @Test
    void missingFileThrowsRatherThanReturningEmptyContent(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.properties");

        assertThatThrownBy(() -> new PropertyFileSource(missing).load())
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void idIsStableForTheSamePath(@TempDir Path dir) {
        Path file = dir.resolve("app.properties");

        assertThat(new PropertyFileSource(file).id()).isEqualTo(new PropertyFileSource(file).id());
    }
}
