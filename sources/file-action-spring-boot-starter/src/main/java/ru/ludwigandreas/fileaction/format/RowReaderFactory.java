package ru.ludwigandreas.fileaction.format;

import java.nio.file.Path;
import ru.ludwigandreas.fileaction.api.RowBinding;

/**
 * Opens a {@link RowReader} for one format.
 *
 * <p>One bean per {@link SourceFormat}, selected by the sniffed format rather than by the declared
 * content type - see {@code FormatSniffer} for why the declared type is not trusted.
 *
 * <h2>Why a local file rather than a stream</h2>
 *
 * <p>This was a stream first, and POI settled it. {@code OPCPackage.open(InputStream)} reads the entire
 * package into the heap before a single sheet can be walked, which is precisely the cost the SAX reader
 * exists to avoid - a 4 MB workbook would still be tens of megabytes resident before any row was seen.
 * Only {@code OPCPackage.open(File, PackageAccess.READ)} reads lazily, out of the ZIP's central
 * directory, which is also what makes the archive inspection below possible at all: entry sizes and
 * counts are in the directory, and a stream has no directory until it has been consumed.
 *
 * <p>So the engine guarantees a local file. That is not a materialisation of the kind this module
 * forbids: the forbidden thing is holding a user's file in the <em>heap</em>, and the module's
 * guarantee is bounded memory. A spooled temp file is bounded by the action's size ceiling, is written
 * once, and is deleted when the reader closes. Spring has already spooled the multipart to
 * {@code java.io.tmpdir} by the time a controller method runs, so at admission this file already exists
 * and nothing is copied; only the confirm and deferred paths, which read from the object store, write
 * one.
 */
public interface RowReaderFactory {

    /** The format this factory reads. */
    SourceFormat format();

    /**
     * Opens a reader over a local copy of the submitted content.
     *
     * @param content a readable local file holding the submission's bytes. Owned by the caller: the
     *                reader does not delete it
     * @param binding what the rows are being read into
     * @param budget  the ceilings this read may not exceed
     * @return a reader; close it
     */
    RowReader open(Path content, RowBinding<?> binding, ReadBudget budget);
}
