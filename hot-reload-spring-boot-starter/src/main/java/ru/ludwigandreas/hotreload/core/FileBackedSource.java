package ru.ludwigandreas.hotreload.core;

import java.nio.file.Path;

/** A {@link ReloadableSource} backed by a single file on disk, watchable by {@link
 * ru.ludwigandreas.hotreload.core.watch.FileResourceWatcher}. */
public interface FileBackedSource extends ReloadableSource {

    Path path();
}
