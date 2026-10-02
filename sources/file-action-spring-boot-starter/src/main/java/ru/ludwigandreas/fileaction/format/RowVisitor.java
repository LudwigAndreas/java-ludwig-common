package ru.ludwigandreas.fileaction.format;

/**
 * What a {@link RowReader} hands each row to.
 *
 * <p>A visitor rather than an {@code Iterator} because the XLSX reader is a SAX parse, which is push-based.
 * Turning a push into a pull needs either a producer thread and a bounded queue, or a buffer of rows, and
 * neither is justified: a thread per upload is a thread pool nobody configured, and a buffer is the
 * materialisation the reader exists to avoid. So the shape follows the parser rather than fighting it, and
 * the CSV reader - which could offer either - offers this one too, so that the engine has one path.
 */
@FunctionalInterface
public interface RowVisitor {

    /**
     * Takes one row.
     *
     * @param row the row, with its cells already resolved against the binding's headers
     * @return true to carry on, false to stop reading. Returning false is how the engine stops at a
     *         ceiling or at a cancellation without reading the rest of a large file
     */
    boolean visit(RawRow row);
}
