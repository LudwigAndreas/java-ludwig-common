package ru.ludwigandreas.identity.directory.service;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.identity.directory.IdentityDirectory;
import ru.ludwigandreas.identity.directory.repository.DirectoryStateStore;
import ru.ludwigandreas.identity.feed.IdentityFeed;

/**
 * A directory, as the projection's startup validation and sources listing see it: its watermark and how its last
 * sync and sweep ended. Never user data.
 */
public final class DirectoryFeed implements IdentityFeed {

    private final IdentityDirectory directory;
    private final DirectoryStateStore state;

    /**
     * Creates the feed.
     *
     * @param directory the directory
     * @param state     its progress
     */
    public DirectoryFeed(IdentityDirectory directory, DirectoryStateStore state) {
        this.directory = directory;
        this.state = state;
    }

    @Override
    public String source() {
        return directory.source();
    }

    @Override
    public String kind() {
        return "directory";
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        state.read(directory.source()).ifPresent(current -> {
            put(status, "watermark", current.watermark());
            status.put("lastSync", run(current.lastSyncAt(), current.lastSyncOutcome(), current.lastSyncError(),
                    current.lastSyncPages(), "changes", current.lastSyncChanges()));
            status.put("lastSweep", run(current.lastSweepAt(), current.lastSweepOutcome(), current.lastSweepError(),
                    current.lastSweepPages(), "disabled", current.lastSweepDisabled()));
        });
        return status;
    }

    private static Map<String, Object> run(Object at, String outcome, String error, Integer pages, String countName,
                                           Integer count) {
        Map<String, Object> run = new LinkedHashMap<>();
        put(run, "at", at);
        put(run, "outcome", outcome);
        put(run, "error", error);
        put(run, "pages", pages);
        put(run, countName, count);
        return run;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
