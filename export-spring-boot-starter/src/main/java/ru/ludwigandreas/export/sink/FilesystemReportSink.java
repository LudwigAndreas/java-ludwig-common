package ru.ludwigandreas.export.sink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.StoredOutput;
import ru.ludwigandreas.export.exception.ExportException;

/**
 * Stores finished reports in a directory on this machine.
 *
 * <p>The one sink this module ships, and a real one rather than a placeholder: a single-instance
 * deployment writing to a mounted volume is a perfectly ordinary way to run this, and it is what
 * the module's own integration tests use. What it is not is a deployment where any instance can
 * serve any download - a run executed on instance A leaves its file on instance A, so a load
 * balancer in front of two instances will serve half the download requests a 404. An estate with
 * more than one instance wants object storage behind {@link ReportSink}, which is why that
 * interface exists before its second implementation.
 *
 * <h2>Layout, and why it is not flat</h2>
 *
 * <p>Files live at {@code <root>/<run-id-prefix>/<run-id>/<file-name>}. The two-character prefix
 * directory exists because a flat directory of report outputs reaches hundreds of thousands of
 * entries on a busy estate, at which point directory operations - including the retention purge's
 * own listing - become the slowest part of the module on every filesystem that does not index
 * directories.
 *
 * <p>The per-run directory is what makes deleting a run's outputs one operation rather than a
 * pattern match, and it is why a multi-format run does not need its file names to be unique across
 * runs.
 */
@Slf4j
public class FilesystemReportSink implements ReportSink {

    /** How many leading characters of the run id become the fan-out directory. */
    private static final int PREFIX_LENGTH = 2;

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final Path root;

    /**
     * Creates the sink over a directory that must already exist and be writable.
     *
     * <p>Deliberately does not create the root. A typo in a configured path that silently created a
     * directory would put a run's worth of production data somewhere nobody is watching and nobody
     * is backing up; failing at startup instead is the whole point of
     * {@code ExportConfigurationValidator} checking the same thing.
     *
     * @param root the directory reports are stored under
     */
    public FilesystemReportSink(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("A FilesystemReportSink needs a root directory");
        }
        if (!Files.isDirectory(root)) {
            throw new ExportException("Export sink root " + root + " does not exist or is not a directory");
        }
        if (!Files.isWritable(root)) {
            throw new ExportException("Export sink root " + root + " is not writable by this process");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredOutput store(UUID runId, String fileName, ReportFormat format, Path file)
            throws IOException {
        Path directory = directoryFor(runId);
        Files.createDirectories(directory);
        Path target = resolveInside(directory, fileName);

        // Move first, hash afterwards, and hash the stored bytes rather than the source. Hashing
        // what was actually written is what makes the checksum answer the question it exists for -
        // "is the file being discussed the one this run produced" - rather than the weaker "is the
        // file the engine intended to write the one it hashed".
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // A cross-device move is not atomic and not supported; copying is correct and is the
            // normal case when the temp directory and the sink root are different volumes, which is
            // exactly how a container that writes temp files to its overlay and outputs to a volume
            // is configured.
            log.debug("Atomic move from {} to {} not possible, copying instead: {}",
                    file, target, e.toString());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }

        String checksum = sha256(target);
        long size = Files.size(target);
        log.debug("Stored report output for run {} as {} ({} bytes, {})", runId, target, size, format.id());
        return new StoredOutput(root.relativize(target).toString(), size, checksum);
    }

    @Override
    public InputStream open(String uri) throws IOException {
        return Files.newInputStream(resolveStored(uri));
    }

    @Override
    public void delete(String uri) throws IOException {
        Path stored = resolveStored(uri);
        // Absence is success: the purge runs repeatedly and has to be able to finish a pass it
        // previously half-completed.
        Files.deleteIfExists(stored);
        Path runDirectory = stored.getParent();
        if (runDirectory != null && runDirectory.startsWith(root) && !runDirectory.equals(root)) {
            try (var entries = Files.list(runDirectory)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(runDirectory);
                }
            }
        }
    }

    private Path directoryFor(UUID runId) {
        String id = runId.toString();
        return root.resolve(id.substring(0, PREFIX_LENGTH)).resolve(id);
    }

    /**
     * Resolves a name under a directory and refuses anything that escapes it.
     *
     * <p>File names reach this sink from a report title, which is a message-bundle value a service
     * controls, and from a format extension - so the traversal this prevents is not a live attack
     * path today. It is here because a sink is exactly the kind of component that later gets a
     * user-supplied file name, and a path check added after that change is one nobody remembers to
     * add.
     */
    private Path resolveInside(Path directory, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A stored report needs a file name");
        }
        Path resolved = directory.resolve(name).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException("Report file name escapes its run directory: " + name);
        }
        return resolved;
    }

    private Path resolveStored(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("A stored output uri is required");
        }
        try {
            Path resolved = root.resolve(uri).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException("Stored output uri escapes the sink root: " + uri);
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Stored output uri is not a valid path: " + uri, e);
        }
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file);
                DigestInputStream digesting = new DigestInputStream(in, digest)) {
            while (digesting.read(buffer) != -1) {
                // Reading is what feeds the digest; the bytes themselves are not needed here, and
                // a report is far too large to hold one in memory to hash it.
                continue;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256; if this one does not, nothing downstream
            // that depends on the checksum is going to work either.
            throw new ExportException("SHA-256 is not available in this JVM", e);
        }
    }
}
