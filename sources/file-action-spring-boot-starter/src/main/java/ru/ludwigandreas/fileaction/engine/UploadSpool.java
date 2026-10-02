package ru.ludwigandreas.fileaction.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A local copy of a submitted file, with its content hash, taken in one pass over the request body.
 *
 * <h2>Why a spool and a hash together</h2>
 *
 * <p>Three things need the submitted bytes: the hash, which is the idempotency key and the stored object's
 * name; the object store, whose {@code put} takes a {@link Path}; and the reader, which needs a local file
 * because POI's streaming path only reads lazily from one. Doing them in three passes would read the request
 * body three times, which is not possible, or buffer it, which is the mistake this module is built to avoid.
 *
 * <p>So the body is written to a temp file through a {@link DigestInputStream} - one pass, one write, no
 * buffer - and the hash falls out of the same traversal.
 *
 * <h2>This is not the materialisation the module forbids</h2>
 *
 * <p>The forbidden thing is holding a user's file in the <em>heap</em>. A temp file is bounded by the action's
 * size ceiling, is written once, and is deleted by {@link #close()}. Spring has in any case already spooled
 * the multipart to {@code java.io.tmpdir} before a controller method runs, so at admission this is a copy of a
 * file that already exists - which is why {@link #ofExistingFile} exists, to avoid even that.
 */
public final class UploadSpool implements AutoCloseable {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(UploadSpool.class);

    private static final String DIGEST = "SHA-256";

    /** How much is copied at a time. 64 KiB is large enough to keep the syscall count down. */
    private static final int COPY_BUFFER = 64 * 1024;

    private final Path file;
    private final String sha256;
    private final long sizeBytes;
    private final boolean owned;

    private UploadSpool(Path file, String sha256, long sizeBytes, boolean owned) {
        this.file = file;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.owned = owned;
    }

    /**
     * Streams a request body to a temp file, hashing as it goes, and refuses it the moment it exceeds the
     * ceiling.
     *
     * <p>The ceiling is enforced <em>while</em> copying rather than from a declared content length, because a
     * declared length is a claim by the client. A chunked upload has none at all, and one that lies is the
     * obvious way to get a large file past a check that trusts it.
     *
     * @param body      the request body
     * @param maxBytes  the largest submission this action accepts
     * @param directory where to write, or null for the JVM's temp directory
     * @return the spool; close it
     * @throws IOException if the body cannot be read or the file cannot be written
     * @throws FileRejectedException if the body exceeds {@code maxBytes}
     */
    public static UploadSpool of(InputStream body, long maxBytes, Path directory) throws IOException {
        MessageDigest digest = newDigest();
        Path target = directory == null
                ? Files.createTempFile("ludwig-file-action-", ".upload")
                : Files.createTempFile(directory, "ludwig-file-action-", ".upload");
        long written = 0;
        try (DigestInputStream digesting = new DigestInputStream(body, digest);
                OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = digesting.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes) {
                    // Thrown here, mid-copy, rather than after. Finishing the copy to report the size would mean
                    // writing however much a client chose to send to a pod's disk before refusing it, which is
                    // the denial of service the ceiling exists to prevent.
                    //
                    // The module's own refusal, not a private exception type converted at the boundary. An
                    // earlier version had one, and it bought nothing: the message, the status and the code all
                    // belong to the ceiling rather than to the spool, and a second exception class meant one
                    // more thing outside web-core's single ProblemDetail pipeline for no gain.
                    throw new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                            FileActionProblemCodes.TOO_LARGE, maxBytes);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failed) {
            deleteQuietly(target);
            throw failed;
        }
        return new UploadSpool(target, HexFormat.of().formatHex(digest.digest()), written, true);
    }

    /**
     * Hashes a file that already exists, without copying it.
     *
     * <p>Used when the caller already holds a local file - a multipart Spring has spooled to disk, or an object
     * streamed out of the store by the confirm path - so that the common case costs one read rather than a read
     * and a write.
     *
     * @param existing the file, which this spool does not own and will not delete
     * @return the spool
     * @throws IOException if the file cannot be read
     */
    public static UploadSpool ofExistingFile(Path existing) throws IOException {
        MessageDigest digest = newDigest();
        long size = Files.size(existing);
        try (DigestInputStream digesting = new DigestInputStream(Files.newInputStream(existing), digest)) {
            byte[] buffer = new byte[COPY_BUFFER];
            while (digesting.read(buffer) != -1) {
                // Read for the digest's side effect only. The bytes are already where they need to be.
                continue;
            }
        }
        return new UploadSpool(existing, HexFormat.of().formatHex(digest.digest()), size, false);
    }

    /** The local file holding the bytes. */
    public Path file() {
        return file;
    }

    /** The content hash, lower-case hex. */
    public String sha256() {
        return sha256;
    }

    /** How many bytes there are. */
    public long sizeBytes() {
        return sizeBytes;
    }

    /** Deletes the temp file, if this spool created it. */
    @Override
    public void close() {
        if (owned) {
            deleteQuietly(file);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(DIGEST + " is required of every JVM", impossible);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException leftBehind) {
            // A temp file that could not be deleted is a disk-space problem for the operator, not a reason to
            // fail a request that has otherwise succeeded - and on the error path, replacing the real failure
            // with this one would hide what actually went wrong.
            LOG.warn("could not delete the upload spool {}", path, leftBehind);
        }
    }

}
