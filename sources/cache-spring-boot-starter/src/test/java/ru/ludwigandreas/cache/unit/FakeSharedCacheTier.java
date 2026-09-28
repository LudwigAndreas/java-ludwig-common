package ru.ludwigandreas.cache.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.shared.SharedCacheTier;
import ru.ludwigandreas.cache.shared.SharedCacheView;
import ru.ludwigandreas.cache.shared.SharedEntry;

/**
 * A shared tier in a map, which can be told to fail.
 *
 * <p>The behaviour most worth testing about the two-tier arrangement is what happens when it does not work: a
 * failed shared eviction leaves every replica serving the stale value, which is strictly worse than the
 * single-tier case, and the whole reason that failure is counted and logged as an incident rather than
 * swallowed. Testing that against a real Redis would mean stopping a container mid-test; a tier that can be
 * told to refuse a delete makes it a three-line test.
 *
 * <p>It also stands in for a second replica. {@link #deliverInvalidation} pushes a key to the subscribers, which
 * is how the cross-replica half of an eviction is tested without a second JVM.
 */
class FakeSharedCacheTier implements SharedCacheTier {

    private final Map<String, String> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Long> writtenAt = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new ArrayList<>();
    private final List<String> published = new ArrayList<>();

    private boolean available = true;
    private boolean failDeletes;
    private boolean grantLeases = true;

    void failDeletes(boolean value) {
        this.failDeletes = value;
    }

    void available(boolean value) {
        this.available = value;
    }

    void grantLeases(boolean value) {
        this.grantLeases = value;
    }

    /** Simulates another replica announcing an eviction. */
    void deliverInvalidation(String renderedKey) {
        listeners.forEach(listener -> listener.accept(renderedKey));
    }

    List<String> published() {
        return published;
    }

    boolean holds(String renderedKey) {
        return entries.containsKey(renderedKey);
    }

    @Override
    @SuppressWarnings("unchecked") // One value type per view, and the tests only ever use one.
    public <V> SharedCacheView<V> viewOf(CacheSettings settings, Class<V> valueType) {
        return new View<>(settings);
    }

    private final class View<V> implements SharedCacheView<V> {

        private final CacheSettings settings;

        View(CacheSettings settings) {
            this.settings = settings;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<SharedEntry<V>> read(String renderedKey) {
            if (!available || !entries.containsKey(renderedKey)) {
                return Optional.empty();
            }
            return Optional.of(new SharedEntry<>((V) values.get(renderedKey),
                    writtenAt.get(renderedKey), values.get(renderedKey) == null));
        }

        @Override
        public void write(String renderedKey, SharedEntry<V> entry) {
            if (!available) {
                return;
            }
            entries.put(renderedKey, settings.name());
            writtenAt.put(renderedKey, entry.writtenAtMillis());
            if (entry.value() != null) {
                values.put(renderedKey, entry.value());
            } else {
                values.remove(renderedKey);
            }
        }

        @Override
        public boolean delete(String renderedKey) {
            if (failDeletes) {
                return false;
            }
            entries.remove(renderedKey);
            values.remove(renderedKey);
            writtenAt.remove(renderedKey);
            return true;
        }

        @Override
        public boolean deleteAll() {
            if (failDeletes) {
                return false;
            }
            entries.clear();
            values.clear();
            writtenAt.clear();
            return true;
        }

        @Override
        public boolean tryAcquireLoadLease(String renderedKey) {
            return grantLeases;
        }

        @Override
        public void publishInvalidation(String renderedKey) {
            published.add(renderedKey);
        }

        @Override
        public void subscribeInvalidations(Consumer<String> listener) {
            listeners.add(listener);
        }
    }
}
