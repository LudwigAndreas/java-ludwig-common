package ru.ludwigandreas.export.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.exception.ExportException;

/**
 * The partial files a run writes before anything is stored, and the guarantee that none of them
 * outlives it.
 *
 * <h2>Why this is a class and not two calls to {@code Files.createTempFile}</h2>
 *
 * <p>At the design point a temp file is four hundred megabytes. Three things therefore have to be
 * true, and none of them is true of the obvious approach:
 *
 * <ul>
 *   <li><b>Deleted on every exit path.</b> Success, failure, cancellation, an exception from the
 *       sink, a JVM shutdown. {@code File.deleteOnExit} covers only the last of those and leaks the
 *       name until the JVM ends, which for a long-running service means it never runs at all.</li>
 *   <li><b>Owner-only from the moment it exists.</b> A report is a bulk extract of production data;
 *       leaving it world-readable in a shared temp directory for the minutes it takes to write is a
 *       disclosure with no upside. Created with the permissions rather than chmod-ed afterwards,
 *       because the window between the two is exactly when a large file is at its most
 *       interesting.</li>
 *   <li><b>Swept at startup.</b> An instance that was killed mid-run left its file behind, and the
 *       lease that reclaims the run says nothing about the bytes. Without a sweep, a reporting
 *       instance fills its disk over a weekend of restarts.</li>
 * </ul>
 *
 * <p>Files are named with a fixed prefix and the run id, so the sweep can tell this module's
 * orphans from everything else sharing the directory - deleting by age alone in {@code /tmp} would
 * be a filesystem-wide garbage collector nobody asked for.
 */
@Slf4j
public class TempFiles {

    /** Marks a file as this module's, so the sweep never touches anything it did not write. */
    static final String PREFIX = "ludwig-export-";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private final Path directory;
    private final Clock clock;

    /**
     * Creates the manager over a directory that must already exist and be writable.
     *
     * @param directory where partial files are written
     * @param clock     injected so the sweep's age comparison is deterministic in tests
     */
    public TempFiles(Path directory, Clock clock) {
        if (directory == null) {
            throw new IllegalArgumentException("TempFiles needs a directory");
        }
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new ExportException(
                    "Export temp directory " + directory + " does not exist or is not writable");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Where partial files are written. */
    public Path directory() {
        return directory;
    }

    /**
     * Creates one empty, owner-only file for a run.
     *
     * @param runId     the run, so the sweep and an operator can attribute the file
     * @param extension the format's file extension, without a dot
     * @return the created file
     * @throws IOException if it cannot be created
     */
    public Path create(UUID runId, String extension) throws IOException {
        String suffix = "." + (extension == null || extension.isBlank() ? "tmp" : extension);
        try {
            return Files.createTempFile(directory, PREFIX + runId + "-", suffix,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException e) {
            // A non-POSIX filesystem - Windows, or an exotic mount. The file still has to exist, and
            // the platform's own default ACL is the best available answer there; saying so in the log
            // is better than silently writing a report somewhere with unknown permissions.
            log.warn("Filesystem at {} does not support POSIX permissions; the report temp file will"
                    + " carry the platform default instead of owner-only", directory);
            return Files.createTempFile(directory, PREFIX + runId + "-", suffix);
        }
    }

    /**
     * Deletes a partial file, never throwing.
     *
     * <p>Called from the failure path, so it cannot be allowed to replace the failure that brought
     * the run here with one about a file - the first is what somebody needs to read.
     *
     * @param file the file, or null
     */
    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete report temp file {}: {}", file, e.toString());
        }
    }

    /**
     * Removes this module's files left behind by an instance that died.
     *
     * @param olderThan how old a file must be to count as an orphan. Anything younger may belong to
     *                  a run executing right now on another instance sharing this directory, and
     *                  deleting it would fail a live report
     * @return how many files were removed
     */
    public int sweepOrphans(Duration olderThan) {
        FileTime threshold = FileTime.from(clock.instant().minus(olderThan));
        int removed = 0;
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path candidate : entries.toList()) {
                if (isOrphan(candidate, threshold)) {
                    deleteQuietly(candidate);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not sweep report temp directory {}: {}", directory, e.toString());
            return removed;
        }
        if (removed > 0) {
            log.info("Swept {} orphaned report temp file(s) older than {} from {}",
                    removed, olderThan, directory);
        }
        return removed;
    }

    private boolean isOrphan(Path candidate, FileTime threshold) {
        String name = candidate.getFileName().toString();
        if (!name.startsWith(PREFIX)) {
            return false;
        }
        try {
            return Files.isRegularFile(candidate)
                    && Files.getLastModifiedTime(candidate).compareTo(threshold) < 0;
        } catch (FileSystemException e) {
            // The file went away between the listing and the stat, which is what a concurrent sweep
            // on another instance looks like. Not an orphan, and not a problem.
            log.debug("Report temp file {} vanished while sweeping: {}", candidate, e.toString());
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
