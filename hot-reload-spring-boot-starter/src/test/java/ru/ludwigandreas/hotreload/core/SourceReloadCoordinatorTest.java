package ru.ludwigandreas.hotreload.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SourceReloadCoordinatorTest {

    @Test
    void firstLoadAlwaysNotifiesWithAllKeysChanged() {
        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        List<SourceChangeEvent> events = new ArrayList<>();
        coordinator.addListener(events::add);

        boolean changed = coordinator.reload(fixedSource("a", Map.of("x", "1", "y", "2")));

        assertThat(changed).isTrue();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).content()).containsEntry("x", "1").containsEntry("y", "2");
        assertThat(events.get(0).changedKeys()).containsExactlyInAnyOrder("x", "y");
    }

    @Test
    void secondLoadWithSameContentDoesNotNotify() {
        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        AtomicInteger notifications = new AtomicInteger();
        coordinator.addListener(event -> notifications.incrementAndGet());

        ReloadableSource source = fixedSource("a", Map.of("x", "1"));
        coordinator.reload(source);
        boolean changedOnSecondCall = coordinator.reload(source);

        assertThat(changedOnSecondCall).isFalse();
        assertThat(notifications).hasValue(1);
    }

    @Test
    void reloadWithDifferentContentReportsOnlyChangedKeys() {
        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        List<SourceChangeEvent> events = new ArrayList<>();
        coordinator.addListener(events::add);

        coordinator.reload(fixedSource("a", Map.of("x", "1", "y", "2")));
        coordinator.reload(fixedSource("a", Map.of("x", "1", "y", "3", "z", "4")));

        assertThat(events).hasSize(2);
        assertThat(events.get(1).changedKeys()).containsExactlyInAnyOrder("y", "z");
    }

    @Test
    void changeEventCarriesOldAndNewValuesForAudit() {
        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        List<SourceChangeEvent> events = new ArrayList<>();
        coordinator.addListener(events::add);

        coordinator.reload(fixedSource("a", Map.of("x", "1")));
        coordinator.reload(fixedSource("a", Map.of("x", "2")));

        SourceChangeEvent secondEvent = events.get(1);
        assertThat(secondEvent.oldValueOf("x")).isEqualTo("1");
        assertThat(secondEvent.newValueOf("x")).isEqualTo("2");
        assertThat(secondEvent.previousContent()).containsEntry("x", "1");

        // first-ever load has no previous state to diff against
        assertThat(events.get(0).previousContent()).isEmpty();
        assertThat(events.get(0).oldValueOf("x")).isNull();
    }

    @Test
    void failedReloadKeepsLastKnownGoodAndReportsError() {
        SourceReloadCoordinator coordinator = new SourceReloadCoordinator();
        List<SourceChangeEvent> events = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        coordinator.addListener(new SourceChangeListener() {
            @Override
            public void onChange(SourceChangeEvent event) {
                events.add(event);
            }

            @Override
            public void onError(String sourceId, Exception exception) {
                errors.add(exception);
            }
        });

        coordinator.reload(fixedSource("a", Map.of("x", "1")));

        RuntimeException failure = new RuntimeException("boom");
        boolean changed = coordinator.reload(new ReloadableSource() {
            @Override
            public String id() {
                return "a";
            }

            @Override
            public Map<String, Object> load() {
                throw failure;
            }
        });

        assertThat(changed).isFalse();
        assertThat(events).hasSize(1);
        assertThat(errors).containsExactly(failure);
        assertThat(coordinator.lastKnownGood("a")).containsEntry("x", "1");
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
