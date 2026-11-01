package ru.ludwigandreas.hotreload.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Shared load-diff-notify logic used by every {@link ru.ludwigandreas.hotreload.core.watch.ResourceWatcher}
 * (file-system and polling alike), so "what changed" and "keep last-known-good on failure" are defined
 * in exactly one place.
 *
 * <p>Thread-safe: {@link #reload(ReloadableSource)} may be called concurrently for different sources
 * (never expected concurrently for the <em>same</em> source, but tolerated - the map operations are
 * atomic per key).
 */
public class SourceReloadCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SourceReloadCoordinator.class);

    private final Map<String, Map<String, Object>> lastKnownGood = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SourceChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> lastReloaded = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rejections = new ConcurrentHashMap<>();
    private final Map<String, ReloadOutcome> lastOutcome = new ConcurrentHashMap<>();

    public void addListener(SourceChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SourceChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Loads {@code source}, compares it against the last successfully loaded content, and - if this is
     * the first load or the content differs - notifies listeners. A failed load never clobbers
     * previously published content; listeners are notified via {@link SourceChangeListener#onError}
     * instead.
     *
     * @return {@code true} if listeners were notified of a change
     */
    public boolean reload(ReloadableSource source) {
        return reloadOutcome(source) == ReloadOutcome.RELOADED;
    }

    /**
     * {@link #reload(ReloadableSource)}, saying what happened: {@link ReloadOutcome#REJECTED} when the source could
     * not be loaded, or when a typed configuration refused the new content during notification (see
     * {@link #rejected(String)}).
     *
     * @param source the source
     * @return the outcome on this replica
     */
    public ReloadOutcome reloadOutcome(ReloadableSource source) {
        ReloadOutcome outcome = load(source);
        lastOutcome.put(source.id(), outcome);
        if (outcome == ReloadOutcome.RELOADED) {
            lastReloaded.put(source.id(), Instant.now());
        }
        return outcome;
    }

    /**
     * Records that a listener refused a change of {@code sourceId} and kept its previous value - a typed
     * configuration whose new content failed validation. Notifies nobody; it only makes the reload in progress
     * report {@link ReloadOutcome#REJECTED}.
     *
     * @param sourceId the source whose change was refused
     */
    public void rejected(String sourceId) {
        rejections.computeIfAbsent(sourceId, id -> new AtomicLong()).incrementAndGet();
    }

    /**
     * When {@code sourceId} last reloaded with changed content on this replica.
     *
     * @param sourceId the source
     * @return the time, when it has
     */
    public Optional<Instant> lastReloaded(String sourceId) {
        return Optional.ofNullable(lastReloaded.get(sourceId));
    }

    /**
     * The outcome of the last reload of {@code sourceId} on this replica, by whichever path it ran.
     *
     * @param sourceId the source
     * @return it, when the source has been reloaded
     */
    public Optional<ReloadOutcome> lastOutcome(String sourceId) {
        return Optional.ofNullable(lastOutcome.get(sourceId));
    }

    private ReloadOutcome load(ReloadableSource source) {
        Map<String, Object> content;
        try {
            content = source.load();
        } catch (Exception e) {
            notifyError(source.id(), e);
            return ReloadOutcome.REJECTED;
        }

        Map<String, Object> previous = lastKnownGood.get(source.id());
        Set<String> changedKeys = diff(previous, content);
        if (previous != null && changedKeys.isEmpty()) {
            return ReloadOutcome.UNCHANGED;
        }

        lastKnownGood.put(source.id(), content);
        SourceChangeEvent event = new SourceChangeEvent(source.id(), content,
                previous == null ? Map.of() : previous, changedKeys);
        log.info("Source '{}' {} ({} key(s) changed)", source.id(), previous == null ? "loaded" : "reloaded",
                changedKeys.size());
        long refusedBefore = rejectionCount(source.id());
        notifyChange(event);
        return rejectionCount(source.id()) > refusedBefore ? ReloadOutcome.REJECTED : ReloadOutcome.RELOADED;
    }

    private long rejectionCount(String sourceId) {
        AtomicLong count = rejections.get(sourceId);
        return count == null ? 0 : count.get();
    }

    public Map<String, Object> lastKnownGood(String sourceId) {
        return lastKnownGood.get(sourceId);
    }

    /**
     * Reports a reload failure that happened outside {@link #reload(ReloadableSource)} itself - e.g. a
     * Vault lease renewal/rotation error surfaced asynchronously by {@code SecretLeaseContainer}, for a
     * source that was never {@link #reload}ed directly in the first place.
     */
    public void notifyError(String sourceId, Exception exception) {
        log.warn("Source '{}' reported a reload failure, keeping last known good content", sourceId, exception);
        forEachListener(l -> l.onError(sourceId, exception));
    }

    private static Set<String> diff(Map<String, Object> previous, Map<String, Object> current) {
        if (previous == null) {
            return current.keySet();
        }
        Set<String> changed = new HashSet<>();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            Object oldValue = previous.get(entry.getKey());
            if (!java.util.Objects.equals(oldValue, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String key : previous.keySet()) {
            if (!current.containsKey(key)) {
                changed.add(key);
            }
        }
        return changed;
    }

    private void notifyChange(SourceChangeEvent event) {
        forEachListener(l -> l.onChange(event));
    }

    private void forEachListener(Consumer<SourceChangeListener> action) {
        for (SourceChangeListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.error("SourceChangeListener {} threw while handling a reload notification", listener, e);
            }
        }
    }
}
