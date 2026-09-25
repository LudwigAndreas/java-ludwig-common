package ru.ludwigandreas.storage.api;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;
import ru.ludwigandreas.storage.exception.ObjectStoreException;

/**
 * The platform's one bucket client.
 *
 * <h2>Why one module owns this</h2>
 *
 * <p>Two things in this platform need a bucket for opposite reasons - the export starter writes
 * finished reports into one, a file ingest reads large drops out of one - and each of them could
 * have grown its own client. That is how {@code job-core}'s {@code JdbcRunLock} and a second,
 * separately written distributed lock came to exist, and the cost was not the duplicated code but
 * the two subtly different answers to "what does a dead holder look like". A second bucket client
 * would give two answers to "is deleting something absent a success", "does listing a year of daily
 * drops fit in memory" and "who retries", and the disagreement would only be discovered in the
 * incident where it mattered.
 *
 * <h2>Ranged reads are not an extra</h2>
 *
 * <p>{@link #open(String, ByteRange)} is the method this interface exists for. A store that could
 * only hand back a whole object would make resuming an interrupted read of a 1 GB drop mean
 * re-downloading the 800 MB already consumed, and a checkpoint that costs 800 MB to honour is not a
 * checkpoint - the design collapses back into "start over", which is the failure mode the checkpoint
 * was introduced to remove. Every implementation must serve ranges natively; emulating one by
 * reading and discarding is a correctness-preserving, cost-destroying substitution and is not
 * acceptable here.
 *
 * <h2>Listing is lazy, and that is part of the contract</h2>
 *
 * <p>{@link #list(String)} returns a {@link Stream} that fetches the next page only when the
 * consumer asks for it. A {@code List} return would be a module that works in every test and falls
 * over the first time somebody points it at a prefix holding a year of daily files. The returned
 * stream holds a connection between pages and <strong>must be closed</strong>; it is an
 * {@link java.io.Closeable} stream, so a try-with-resources is the only correct way to consume it.
 *
 * <h2>Failure, and who retries</h2>
 *
 * <p>Implementations do <strong>not</strong> retry internally, for the reason {@code ReportSink}
 * gives about itself: the caller already has a backoff budget - the export engine's store attempts,
 * an ingest run's own restart - and a second budget underneath it multiplies rather than adds. Two
 * layers of three attempts with exponential backoff is nine attempts and a wait nobody chose.
 *
 * <p>The SDK has a retry policy of its own, and it is on by default, so "we do not retry" is a claim
 * that has to be made true rather than merely stated: {@code S3ObjectStore} configures the SDK's
 * policy down to a small, explicit number of attempts that covers a redirect or a throttle and
 * nothing more. See that class for the number and why it is not zero.
 *
 * <p>Everything throws unchecked. {@link ObjectNotFoundException} for an object that is not there,
 * {@link ObjectStoreException} for everything else, both of them {@code LocalizedException}s that
 * {@code web-core}'s single {@code ProblemDetail} pipeline renders without this module shipping a
 * {@code @RestControllerAdvice}. Checked exceptions were considered and rejected: the two callers
 * this exists for are a report engine and a batch job, neither of which can do anything at the call
 * site that a {@code catch} further out cannot do better, and an {@code IOException} on every
 * signature would have forced both of them to wrap every call.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Implementations are thread-safe and are used as singletons. The streams they return are not,
 * and belong to the thread that asked for them.
 */
public interface ObjectStore {

    /**
     * Reads an object's metadata without reading the object.
     *
     * @param uri the location, {@code s3://bucket/key} or {@code file:///path}
     * @return the object's size, content identity and timestamps
     * @throws ObjectNotFoundException if nothing is stored there
     * @throws ObjectStoreException    if the store could not be reached or refused the request
     */
    StoredObject head(String uri);

    /**
     * Opens the whole object.
     *
     * <p>The caller closes the stream, and should close it even when it stops reading early: an
     * abandoned S3 response stream holds its connection until the pool reclaims it, and a job that
     * abandons one per file exhausts the pool rather than failing visibly.
     *
     * @param uri the location
     * @return the bytes, from the first to the last
     * @throws ObjectNotFoundException if nothing is stored there
     * @throws ObjectStoreException    if the store could not be reached or refused the request
     */
    InputStream open(String uri);

    /**
     * Opens part of an object.
     *
     * <p>A range starting at or past the end of the object yields an <strong>empty stream</strong>,
     * not an error, in every implementation - see {@link ByteRange} for why that case is normal
     * rather than exceptional, and why the two implementations are held to the same answer by one
     * shared test.
     *
     * @param uri   the location
     * @param range the window to read
     * @return the bytes in the window; possibly fewer than requested if the object is shorter
     * @throws ObjectNotFoundException if nothing is stored there
     * @throws ObjectStoreException    if the store could not be reached or refused the request
     */
    InputStream open(String uri, ByteRange range);

    /**
     * Lists everything whose key starts with a prefix, one page at a time.
     *
     * <p>The stream is lazy and holds resources between pages. Consume it inside a
     * try-with-resources; a caller that does not close it leaks a connection per listing.
     *
     * <p>Ordering is the store's own - S3 returns keys in lexicographic order and the filesystem
     * store sorts to match, so that a test written against one is meaningful against the other. It
     * is not a guarantee a caller should depend on for correctness; a caller that needs a specific
     * file should name it rather than assume its position.
     *
     * @param prefix a location whose key is treated as a prefix, for example
     *               {@code s3://drop/catalogue/}
     * @return a lazily paged stream of entries; never materialised in full
     * @throws ObjectStoreException if the store could not be reached or refused the request
     */
    Stream<ObjectSummary> list(String prefix);

    /**
     * Stores a local file, replacing whatever was there.
     *
     * <p>Takes a {@link Path} rather than an {@link InputStream} on purpose: a store needs the
     * content length before it sends the first byte, and the only way to get one from a stream is to
     * buffer the whole thing - which is how a module that promises never to hold a file in memory
     * ends up holding one.
     *
     * @param uri     where to put it
     * @param file    the local file to upload
     * @param options what to record alongside it
     * @return the metadata of what was stored, including the etag the store assigned
     * @throws ObjectStoreException if the file could not be read, or the store refused it
     */
    StoredObject put(String uri, Path file, PutOptions options);

    /**
     * Copies an object within this store, without moving its bytes through this process.
     *
     * <p>Its own method rather than an open-and-put, because both implementations can do it without
     * a round trip through here - S3 with {@code CopyObject}, the filesystem with a filesystem copy -
     * and an archival step that streamed a 1 GB file down and back up would cost two transfers of
     * the whole object to move it between two prefixes of the same bucket.
     *
     * @param sourceUri what to copy
     * @param targetUri where to put the copy
     * @return the metadata of the copy
     * @throws ObjectNotFoundException if the source is not there
     * @throws ObjectStoreException    if the copy failed
     */
    StoredObject copy(String sourceUri, String targetUri);

    /**
     * Removes an object.
     *
     * <p>Deleting something that is not there is a <strong>success</strong>. Every caller of this is
     * a step in a sequence that can be interrupted and re-run - an archival that already moved the
     * file, a retention purge that already removed half a batch - and a delete that failed on
     * absence would make every one of them unable to finish a pass it had previously half-completed.
     *
     * @param uri the location
     * @throws ObjectStoreException if it exists and could not be removed
     */
    void delete(String uri);

    /**
     * Whether an object is there.
     *
     * <p>A separate method rather than {@code head} in a {@code try}, because "is it there" is a
     * question with a boolean answer and writing it as exception control flow at every call site is
     * both slower to read and easy to get wrong - a {@code catch} broad enough to cover the
     * not-found case usually also swallows the store being unreachable, and then a missing bucket
     * looks exactly like a missing file.
     *
     * @param uri the location
     * @return {@code true} if an object is stored there
     * @throws ObjectStoreException if the store could not be reached
     */
    boolean exists(String uri);
}
