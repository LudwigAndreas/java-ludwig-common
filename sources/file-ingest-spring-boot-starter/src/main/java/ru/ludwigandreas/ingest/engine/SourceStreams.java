package ru.ludwigandreas.ingest.engine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import ru.ludwigandreas.ingest.api.Checkpoint;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.exception.IngestException;
import ru.ludwigandreas.storage.api.ByteRange;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectUri;

/**
 * Opens the source object at the right place, decompressing if it needs to be.
 *
 * <h2>Compression is what decides whether a ranged resume is possible at all</h2>
 *
 * <p>A byte-offset checkpoint counts <em>uncompressed</em> bytes, because that is what the parser
 * sees. For an uncompressed object those are the same bytes the store holds, so the resume is a ranged
 * GET from the offset and nothing already consumed is transferred again - which is the whole point of
 * the ranged read in {@code ObjectStore}.
 *
 * <p>For a gzipped object they are not the same bytes, and there is no general way to map one to the
 * other: gzip is a single stream and seeking into it means decompressing everything before the target.
 * So a gzipped resume re-reads from the beginning and discards, which costs the transfer and the
 * decompression of everything already consumed and is still correct. That is a real cost, it is stated
 * here and in the README rather than hidden, and the answer for a partner whose files are large enough
 * for it to matter is to ask them for a format that can be seeked - not to pretend the offset means
 * something it does not.
 *
 * <p>The distinction is <em>not</em> the same as the checkpoint kind. A CSV inside a gzip still has a
 * byte offset that is a record boundary, so its checkpoint is a {@link Checkpoint.ByteOffset} and its
 * balance and its counts are all exact; what it loses is only the cheap seek.
 */
public final class SourceStreams {

    private static final String GZIP_SUFFIX = ".gz";

    /**
     * Buffer between the HTTP response and the decompressor.
     *
     * <p>GZIPInputStream reads the compressed stream a few bytes at a time, and the stream underneath
     * is a socket: without a buffer, a 1 GB object becomes tens of millions of tiny reads.
     */
    private static final int GZIP_BUFFER_BYTES = 64 * 1024;

    private SourceStreams() {
    }

    /**
     * Whether the object is gzipped, per the task's configuration.
     *
     * @param uri         the object
     * @param compression the task's setting
     * @return {@code true} if the stream must be decompressed
     */
    public static boolean isCompressed(ObjectUri uri, FileIngestProperties.Compression compression) {
        return switch (compression) {
            case GZIP -> true;
            case NONE -> false;
            case AUTO -> uri.name().toLowerCase(Locale.ROOT).endsWith(GZIP_SUFFIX);
        };
    }

    /**
     * Opens the object positioned at the checkpoint, decompressed if required.
     *
     * <p>Three cases, and the difference between them is exactly the cost of the resume:
     *
     * <ul>
     *   <li>uncompressed, byte-offset checkpoint: a <strong>ranged</strong> GET from the offset;</li>
     *   <li>compressed: a full GET, and the caller skips forward - the offset cannot be mapped;</li>
     *   <li>record-ordinal checkpoint: a full GET, and the parser skips records forward.</li>
     * </ul>
     *
     * @param store       where the object is
     * @param uri         the object
     * @param checkpoint  where to resume from
     * @param compression the task's compression setting
     * @return the stream, positioned as close to the checkpoint as the format allows, and whether the
     *         caller still has to skip
     */
    public static Opened open(ObjectStore store, ObjectUri uri, Checkpoint checkpoint,
                              FileIngestProperties.Compression compression) {
        boolean compressed = isCompressed(uri, compression);
        boolean seekable = !compressed && checkpoint instanceof Checkpoint.ByteOffset;
        long position = checkpoint.position();

        InputStream raw = seekable && position > 0
                ? store.open(uri.value(), ByteRange.from(position))
                : store.open(uri.value());

        InputStream stream = compressed ? gunzip(raw, uri) : raw;
        long alreadyPositioned = seekable ? position : 0;
        return new Opened(stream, alreadyPositioned, !seekable && position > 0);
    }

    private static InputStream gunzip(InputStream raw, ObjectUri uri) {
        try {
            return new GZIPInputStream(new BufferedInputStream(raw, GZIP_BUFFER_BYTES),
                    GZIP_BUFFER_BYTES);
        } catch (IOException e) {
            closeQuietly(raw);
            throw new IngestException("Could not open " + uri.value() + " as a gzip stream", e);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Nothing useful to do: the caller is already failing for a better reason.
            throw new IngestException("Could not close the source stream after a failed open", e);
        }
    }

    /**
     * An opened source stream and what the caller still has to do to reach the checkpoint.
     *
     * @param stream           the content
     * @param startOffset      the uncompressed byte offset the stream begins at, which the parser
     *                         needs in order to report absolute offsets
     * @param mustSkipForward  whether the caller still has to discard bytes or records to reach the
     *                         checkpoint, because the stream could not be positioned
     */
    public record Opened(InputStream stream, long startOffset, boolean mustSkipForward) {
    }
}
