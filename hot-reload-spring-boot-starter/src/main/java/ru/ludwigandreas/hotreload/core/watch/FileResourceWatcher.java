package ru.ludwigandreas.hotreload.core.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.hotreload.core.FileBackedSource;
import ru.ludwigandreas.hotreload.core.HotReloadException;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Watches the parent directories of a set of {@link FileBackedSource}s with a {@link WatchService} and
 * reloads a directory's sources whenever anything in it changes.
 *
 * <p>Deliberately reacts to <em>any</em> event in a watched directory rather than filtering by filename:
 * Kubernetes ConfigMap/Secret volume mounts (and the Vault Agent Injector's rendered-template sidecar)
 * publish updates by atomically re-pointing a {@code ..data} symlink at a new versioned directory, which
 * fires directory-level create/delete events rather than a modify event on the file itself. Filtering by
 * filename would miss exactly the update mechanism this is built for.
 *
 * <p>Reloads are debounced: a burst of filesystem events for the same directory (as happens during the
 * multi-step symlink swap above) collapses into a single reload once the directory goes quiet.
 */
public class FileResourceWatcher implements ResourceWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileResourceWatcher.class);

    private final List<FileBackedSource> sources;
    private final SourceReloadCoordinator coordinator;
    private final Duration debounce;

    private volatile WatchService watchService;
    private volatile Thread watchThread;
    private volatile ScheduledExecutorService debounceExecutor;
    private final Map<Path, ScheduledFuture<?>> pendingReloads = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchedDirectories = new ConcurrentHashMap<>();
    private final Map<Path, List<FileBackedSource>> sourcesByDirectory;

    public FileResourceWatcher(List<FileBackedSource> sources, SourceReloadCoordinator coordinator, Duration debounce) {
        this.sources = List.copyOf(sources);
        this.coordinator = coordinator;
        this.debounce = debounce;
        this.sourcesByDirectory = this.sources.stream()
                .collect(Collectors.groupingBy(FileResourceWatcher::watchedDirectoryOf));
    }

    /**
     * The directory to watch for a source: the source's own path if it already names a directory (e.g.
     * {@link ru.ludwigandreas.hotreload.template.FreemarkerTemplateDirectorySource}, which represents an
     * entire template directory rather than one file), otherwise that file's parent directory.
     */
    private static Path watchedDirectoryOf(FileBackedSource source) {
        Path path = source.path().toAbsolutePath().normalize();
        return Files.isDirectory(path) ? path : path.getParent();
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        sources.forEach(coordinator::reload);
        if (sourcesByDirectory.isEmpty()) {
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (Path directory : sourcesByDirectory.keySet()) {
                WatchKey key = directory.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchedDirectories.put(key, directory);
            }
        } catch (IOException e) {
            throw new HotReloadException("Failed to start filesystem watch for hot-reloadable files", e);
        }

        debounceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "hotreload-file-debounce");
            t.setDaemon(true);
            return t;
        });

        watchThread = new Thread(this::watchLoop, "hotreload-file-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    @Override
    public synchronized void stop() {
        Thread thread = watchThread;
        watchThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        WatchService service = watchService;
        watchService = null;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                log.debug("Error closing watch service", e);
            }
        }
        ScheduledExecutorService executor = debounceExecutor;
        debounceExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        pendingReloads.clear();
        watchedDirectories.clear();
    }

    @Override
    public boolean isRunning() {
        return watchThread != null && watchThread.isAlive();
    }

    private void watchLoop() {
        WatchService service = watchService;
        while (service != null && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            List<WatchEvent<?>> events = key.pollEvents();
            Path directory = watchedDirectories.get(key);
            boolean valid = key.reset();
            if (!valid) {
                watchedDirectories.remove(key);
            }
            if (directory != null && !events.isEmpty()) {
                scheduleDebouncedReload(directory);
            }
        }
    }

    private void scheduleDebouncedReload(Path directory) {
        ScheduledExecutorService executor = debounceExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        pendingReloads.compute(directory, (dir, existing) -> {
            if (existing != null) {
                existing.cancel(false);
            }
            return executor.schedule(() -> reloadDirectory(dir), debounce.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    private void reloadDirectory(Path directory) {
        pendingReloads.remove(directory);
        List<FileBackedSource> affected = sourcesByDirectory.get(directory);
        if (affected == null) {
            return;
        }
        affected.forEach(coordinator::reload);
    }
}
