package ru.ludwigandreas.hotreload.core;

import java.util.Map;
import java.util.Set;

/**
 * Emitted by a watcher whenever a {@link ReloadableSource} is detected to have changed.
 *
 * @param sourceId        {@link ReloadableSource#id()} of the source that changed
 * @param content         the freshly loaded content (already the new state, not a diff)
 * @param previousContent the content as of the previous successful load; empty on the very first load
 *                        of a source (there is no "previous" to diff against)
 * @param changedKeys     keys added, removed, or whose value changed since {@link #previousContent}
 */
public record SourceChangeEvent(String sourceId, Map<String, Object> content, Map<String, Object> previousContent,
                                 Set<String> changedKeys) {

    public SourceChangeEvent {
        content = Map.copyOf(content);
        previousContent = Map.copyOf(previousContent);
        changedKeys = Set.copyOf(changedKeys);
    }

    /** The value {@code key} had before this change, or {@code null} if it's newly added. */
    public Object oldValueOf(String key) {
        return previousContent.get(key);
    }

    /** The value {@code key} has after this change, or {@code null} if it was removed. */
    public Object newValueOf(String key) {
        return content.get(key);
    }
}
