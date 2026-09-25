package ru.ludwigandreas.export.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Where finished files go, and where downloads read them back from.
 *
 * <h2>Why this interface is three methods rather than one</h2>
 *
 * <p>It was written before the second implementation existed, in the expectation that the one
 * everybody would actually want was object storage and that it had to be able to arrive without an
 * engine change. It has since arrived: {@code S3ReportSink} delegates to the platform's
 * {@link ru.ludwigandreas.storage.api.ObjectStore}, and the engine did not change. The shape held
 * because each method answers a need that outlives any one implementation - storing is the obvious
 * one; {@code open} is what the download endpoint needs and what a local {@code File} return would
 * have made unimplementable for a remote store; {@code delete} is what the retention purge needs,
 * and a sink that could not delete would make retention a manual operation against whatever bucket
 * somebody chose.
 *
 * <p>Both shipped implementations are real. A single-instance deployment writing to a mounted volume
 * is a real deployment and is the one the filesystem sink serves; what it is not is a deployment
 * where a download can be served by whichever instance receives the request, and that is the one
 * {@code S3ReportSink} exists for. Which is in use is {@code ludwig.export.sink.type}.
 *
 * <p>Implementations are called from the run's own thread and may block. They must not retry
 * internally: the engine retries a failed store with its own backoff and gives up into a
 * {@code FAILED} run with the temp file deleted, so a sink that also retried would multiply the two
 * budgets together.
 */
public interface ReportSink {

    /**
     * Takes a finished file.
     *
     * <p>The file is the engine's temp file. A sink must copy or move it and must not assume it
     * still exists afterwards - the engine deletes it on return, including when the sink threw.
     *
     * @param runId    the run that produced it
     * @param fileName the file name to present to a downloader, already sanitised and carrying the
     *                 format's extension
     * @param format   the format, for a sink that stores a content type
     * @param file     the finished temp file
     * @return where it went, how big it was, and what it hashed to
     * @throws IOException if the file could not be stored; the engine retries with backoff and then
     *                     fails the run
     */
    StoredOutput store(UUID runId, String fileName, ReportFormat format, Path file) throws IOException;

    /**
     * Reads a stored file back, for the download endpoint.
     *
     * @param uri the {@link StoredOutput#uri()} recorded when it was stored
     * @return the bytes; the caller closes the stream
     * @throws IOException if it cannot be read, including because it is gone
     */
    InputStream open(String uri) throws IOException;

    /**
     * Removes a stored file, for the retention purge.
     *
     * <p>Deleting something already absent is a success, not an error: the purge runs repeatedly and
     * has to be able to finish a run it previously half-completed.
     *
     * @param uri the {@link StoredOutput#uri()} recorded when it was stored
     * @throws IOException if it exists and could not be removed
     */
    void delete(String uri) throws IOException;
}
