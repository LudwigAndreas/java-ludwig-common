package ru.ludwigandreas.export.sink;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.api.PutOptions;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;
import ru.ludwigandreas.storage.exception.ObjectStoreException;

/**
 * Stores finished reports in object storage.
 *
 * <p>The sink {@code ReportSink}'s own documentation said the platform would eventually want, and the
 * reason that interface was three methods rather than one from the start. It is a thin adapter over
 * {@link ObjectStore} and deliberately contains no bucket client of its own: a second client written
 * here would be the {@code JdbcRunLock} mistake happening again, with export and file ingest
 * disagreeing about retries, about whether deleting an absent object is a success, and about how a
 * location is spelled.
 *
 * <h2>What this fixes about the filesystem sink</h2>
 *
 * <p>A run executed on instance A leaves its file on instance A, so a load balancer in front of two
 * instances serves half the download requests a 404. Every instance can read every object here, which
 * is the whole reason an estate with more than one replica needs this sink.
 *
 * <h2>Layout, and why it keeps the filesystem sink's shape</h2>
 *
 * <p>Keys are {@code <prefix>/<run-id-prefix>/<run-id>/<file-name>}. The two-character fan-out
 * directory buys nothing in S3, which has no directories and no per-prefix listing cost of the kind a
 * flat filesystem directory has. It is kept anyway so that the two sinks produce the same shape: an
 * estate migrating from one to the other can copy the tree across unchanged, and an operator reading a
 * stored uri does not have to know which sink wrote it.
 *
 * <h2>Retries</h2>
 *
 * <p>None here, and none underneath: {@code ObjectStore} implementations do not retry and the SDK's
 * own policy is configured down to one mechanical retry by the storage module. {@code RetryingReportSink}
 * wraps this the same way it wraps the filesystem sink, so the store attempts a run is allowed remain
 * the number in {@code ludwig.export.sink.store-attempts} rather than that number multiplied by
 * whatever the SDK was doing.
 */
@Slf4j
public class S3ReportSink implements ReportSink {

    /** How many leading characters of the run id become the fan-out segment. */
    private static final int PREFIX_LENGTH = 2;

    private static final int DIGEST_BUFFER_BYTES = 64 * 1024;

    private final ObjectStore store;
    private final String bucket;
    private final String keyPrefix;

    /**
     * Creates the sink over a bucket.
     *
     * @param store     the platform's object store
     * @param bucket    the bucket finished reports go into
     * @param keyPrefix an optional prefix within the bucket, so that reports can share a bucket with
     *                  something else; may be blank
     */
    public S3ReportSink(ObjectStore store, String bucket, String keyPrefix) {
        if (store == null) {
            throw new IllegalArgumentException("An S3ReportSink needs an ObjectStore");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new ExportException("ludwig.export.sink.bucket is required when sink.type is 's3'");
        }
        this.store = store;
        this.bucket = bucket;
        this.keyPrefix = normalisePrefix(keyPrefix);
    }

    @Override
    public StoredOutput store(UUID runId, String fileName, ReportFormat format, Path file)
            throws IOException {
        String key = keyFor(runId, fileName);
        String uri = ObjectUri.ofS3(bucket, key).value();
        // Hash before the upload rather than after it. The filesystem sink hashes the stored bytes
        // because it can - the file is right there - but reading a just-uploaded object back to hash
        // it would double the transfer of every report for a check that a re-read cannot make
        // stronger: S3 verifies the body against the checksum it computes itself and rejects a
        // truncated upload, so a put that returned is a put that arrived intact.
        String checksum = sha256(file);
        long size = Files.size(file);
        try {
            store.put(uri, file, PutOptions.ofContentType(format.mediaType()));
        } catch (ObjectStoreException e) {
            // Translated into IOException because that is what the interface declares and what
            // RetryingReportSink catches; the cause carries the SDK's own detail into the log.
            throw new IOException("Could not store report output for run " + runId + " at " + uri, e);
        }
        log.debug("Stored report output for run {} at {} ({} bytes, {})", runId, uri, size, format.id());
        return new StoredOutput(uri, size, checksum);
    }

    @Override
    public InputStream open(String uri) throws IOException {
        try {
            return store.open(uri);
        } catch (ObjectNotFoundException e) {
            // A stored output that is gone is a missing file, not an unavailable store, and the
            // download endpoint distinguishes the two.
            throw new FileNotFoundException(uri);
        } catch (ObjectStoreException e) {
            throw new IOException("Could not read stored report output " + uri, e);
        }
    }

    @Override
    public void delete(String uri) throws IOException {
        try {
            // Absence is already a success in ObjectStore#delete, which is the same contract
            // ReportSink#delete states, so there is nothing to reconcile between the two here.
            store.delete(uri);
        } catch (ObjectStoreException e) {
            throw new IOException("Could not delete stored report output " + uri, e);
        }
    }

    private String keyFor(UUID runId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("A stored report needs a file name");
        }
        if (fileName.contains("/") || fileName.contains("\\")) {
            // A key is not a path, so a separator in a file name would silently create a level of
            // nesting nobody asked for rather than being rejected by the store. The filesystem sink
            // refuses the same thing for the stronger reason that it would escape the run directory;
            // refusing it in both keeps the two sinks producing the same keys.
            throw new IllegalArgumentException("Report file name must not contain a path separator: "
                    + fileName);
        }
        String id = runId.toString();
        return keyPrefix + id.substring(0, PREFIX_LENGTH) + "/" + id + "/" + fileName;
    }

    private static String normalisePrefix(String configured) {
        if (configured == null || configured.isBlank()) {
            return "";
        }
        String trimmed = configured.strip();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256; if this one does not, nothing downstream that
            // depends on the checksum is going to work either.
            throw new ExportException("SHA-256 is not available in this JVM", e);
        }
        byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file);
                DigestInputStream digesting = new DigestInputStream(in, digest)) {
            while (digesting.read(buffer) != -1) {
                // Reading is what feeds the digest; a finished report is far too large to hold in
                // memory to hash it.
                continue;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
