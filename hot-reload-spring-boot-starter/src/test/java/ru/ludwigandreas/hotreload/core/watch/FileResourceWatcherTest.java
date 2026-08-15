package ru.ludwigandreas.hotreload.core.watch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.hotreload.core.FileBackedSource;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.hotreload.properties.PropertyFileSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FileResourceWatcherTest {

    private FileResourceWatcher watcher;

    @AfterEach
    void stop() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    @Test
    void startPerformsInitialSynchronousLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "greeting=hello", StandardCharsets.UTF_8);

        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        FileBackedSource source = new PropertyFileSource(file);
        watcher = new FileResourceWatcher(List.of(source), coordinator, Duration.ofMillis(50));

        watcher.start();

        assertThat(coordinator.lastKnownGood(source.id())).containsEntry("greeting", "hello");
        assertThat(watcher.isRunning()).isTrue();
    }

    @Test
    void detectsFileModificationAndReloads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "greeting=hello", StandardCharsets.UTF_8);

        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        List<SourceChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch changed = new CountDownLatch(1);
        coordinator.addListener(event -> {
            events.add(event);
            if (event.changedKeys().contains("greeting")
                    && "goodbye".equals(event.content().get("greeting"))) {
                changed.countDown();
            }
        });

        FileBackedSource source = new PropertyFileSource(file);
        watcher = new FileResourceWatcher(List.of(source), coordinator, Duration.ofMillis(100));
        watcher.start();

        // give the watch service a moment to register before mutating the file
        Thread.sleep(200);
        Files.writeString(file, "greeting=goodbye", StandardCharsets.UTF_8);

        assertThat(changed.await(15, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stopStopsTheWatchThread(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "a=1", StandardCharsets.UTF_8);

        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        watcher = new FileResourceWatcher(List.of(new PropertyFileSource(file)), coordinator, Duration.ofMillis(50));
        watcher.start();
        assertThat(watcher.isRunning()).isTrue();

        watcher.stop();
        assertThat(watcher.isRunning()).isFalse();
    }
}
