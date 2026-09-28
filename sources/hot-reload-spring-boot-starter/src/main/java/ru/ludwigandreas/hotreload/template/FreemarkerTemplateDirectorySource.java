package ru.ludwigandreas.hotreload.template;

import ru.ludwigandreas.hotreload.core.FileBackedSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Represents an entire FreeMarker template directory as a single {@link FileBackedSource} so {@link
 * ru.ludwigandreas.hotreload.core.watch.FileResourceWatcher} watches the directory itself (see {@code
 * FileResourceWatcher.watchedDirectoryOf}) rather than one specific file - templates are added, removed
 * and edited freely, and any of those should trigger {@link HotReloadableTemplateLoader#invalidateAll()}.
 *
 * <p>{@link #load()} carries no real content - the templates themselves are read lazily by FreeMarker
 * through {@link HotReloadableTemplateLoader}, not eagerly here - it only exists to give {@link
 * ru.ludwigandreas.hotreload.core.SourceReloadCoordinator} something to diff so a {@code
 * ConfigurationRefreshedEvent} still fires for observability.
 */
public class FreemarkerTemplateDirectorySource implements FileBackedSource {

    private final Path directory;

    public FreemarkerTemplateDirectorySource(Path directory) {
        this.directory = directory;
    }

    @Override
    public String id() {
        return "freemarker-templates:" + directory.toAbsolutePath().normalize();
    }

    @Override
    public Path path() {
        return directory;
    }

    @Override
    public Map<String, Object> load() {
        return Map.of("reloadedAt", Instant.now().toString());
    }
}
