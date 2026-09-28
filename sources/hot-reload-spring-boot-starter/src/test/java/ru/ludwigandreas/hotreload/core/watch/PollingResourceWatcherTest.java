package ru.ludwigandreas.hotreload.core.watch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.hotreload.core.ReloadableSource;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PollingResourceWatcherTest {

    private PollingResourceWatcher watcher;

    @AfterEach
    void stop() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    @Test
    void pollsOnIntervalAndReportsEachChange() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger();
        ReloadableSource source = new ReloadableSource() {
            @Override
            public String id() {
                return "counter";
            }

            @Override
            public Map<String, Object> load() {
                return Map.of("value", String.valueOf(callCount.incrementAndGet()));
            }
        };

        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        List<SourceChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        coordinator.addListener(event -> {
            events.add(event);
            latch.countDown();
        });

        watcher = new PollingResourceWatcher(List.of(source), coordinator, Duration.ofMillis(20), Duration.ZERO);
        watcher.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(watcher.isRunning()).isTrue();
        assertThat(events.size()).isGreaterThanOrEqualTo(3);
        // every poll changed the value, so every poll should have produced an event
        assertThat(events).extracting(e -> e.content().get("value")).doesNotHaveDuplicates();
    }

    @Test
    void stopPreventsFurtherPolling() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger();
        ReloadableSource source = new ReloadableSource() {
            @Override
            public String id() {
                return "counter";
            }

            @Override
            public Map<String, Object> load() {
                return Map.of("value", String.valueOf(callCount.incrementAndGet()));
            }
        };

        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        watcher = new PollingResourceWatcher(List.of(source), coordinator, Duration.ofMillis(20), Duration.ZERO);
        watcher.start();
        Thread.sleep(100);
        watcher.stop();

        int countAtStop = callCount.get();
        Thread.sleep(150);

        assertThat(callCount.get()).isEqualTo(countAtStop);
        assertThat(watcher.isRunning()).isFalse();
    }
}
