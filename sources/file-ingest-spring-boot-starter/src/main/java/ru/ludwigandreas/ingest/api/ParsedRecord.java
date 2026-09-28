package ru.ludwigandreas.ingest.api;

/**
 * One record, with everything the engine needs to checkpoint it, bound it, or quarantine it.
 *
 * <h2>Why the parser returns this and not just {@code R}</h2>
 *
 * <p>Three of the module's guarantees are impossible without it, and all three are about what happens
 * when something goes wrong:
 *
 * <ul>
 *   <li><b>{@link #endOffset()}</b> is what a {@link Checkpoint.ByteOffset} advances to. Only the
 *       parser knows where a record ended, because only the parser knows where the delimiter was;
 *       the engine counting bytes as they pass would be counting the bytes it decompressed, not the
 *       bytes in the object.</li>
 *   <li><b>{@link #sizeBytes()}</b> is what the byte bound on a batch is measured in, and what makes
 *       a single oversized record identifiable as a poison record rather than as an
 *       {@code OutOfMemoryError} with no context.</li>
 *   <li><b>{@link #raw()}</b> is what a quarantine row stores. A quarantined record whose text was
 *       not kept is a row saying "something on line 400,000 failed", which nobody can act on.</li>
 * </ul>
 *
 * <p>The raw text is truncated by the engine at the task's configured cap before it is stored, not
 * here - the parser hands over what it read, and the decision about how much of it is worth keeping
 * is operational rather than structural.
 *
 * @param <R>        the author's record type
 * @param value      the parsed record, or {@code null} when {@link #failure()} is set
 * @param ordinal    this record's index in the object, counting from zero, across the whole object
 *                   rather than from the resume point
 * @param startOffset the byte offset of this record's first byte in the uncompressed stream
 * @param endOffset  the byte offset of the first byte <em>after</em> this record, which is what a
 *                   byte-offset checkpoint advances to
 * @param sizeBytes  how many bytes this record occupied
 * @param raw        the record's own text, for a quarantine row; never null, possibly empty for a
 *                   binary format where there is no meaningful text
 * @param failure    why the record could not be parsed, or {@code null} when it parsed
 */
public record ParsedRecord<R>(R value, long ordinal, long startOffset, long endOffset, long sizeBytes,
                              String raw, Throwable failure) {

    /**
     * Normalises the record.
     *
     * @throws IllegalArgumentException if the offsets or the ordinal are inconsistent
     */
    public ParsedRecord {
        if (ordinal < 0) {
            throw new IllegalArgumentException("A record ordinal cannot be negative: " + ordinal);
        }
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "A record cannot end before it starts: " + startOffset + ".." + endOffset);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("A record cannot have a negative size: " + sizeBytes);
        }
        raw = raw == null ? "" : raw;
    }

    /**
     * A record that parsed.
     *
     * @param <R>         the record type
     * @param value       the parsed record
     * @param ordinal     its index in the object
     * @param startOffset its first byte
     * @param endOffset   the first byte after it
     * @param raw         its own text
     * @return the record
     */
    public static <R> ParsedRecord<R> parsed(R value, long ordinal, long startOffset, long endOffset,
                                             String raw) {
        return new ParsedRecord<>(value, ordinal, startOffset, endOffset, endOffset - startOffset, raw, null);
    }

    /**
     * A record that did not parse.
     *
     * <p>Returned rather than thrown, and that is the whole design: a parser that threw would end the
     * run at the first malformed line, which is what {@code fail-fast} is for and is not the default.
     * Handing the failure back as a record lets the engine apply the task's quarantine policy, keep
     * the offset, and carry on - which is what turns one bad row in four million into a quarantine row
     * instead of an incident.
     *
     * @param <R>         the record type
     * @param ordinal     its index in the object
     * @param startOffset its first byte
     * @param endOffset   the first byte after it
     * @param raw         its own text, for the quarantine row
     * @param failure     what went wrong
     * @return the record
     */
    public static <R> ParsedRecord<R> failed(long ordinal, long startOffset, long endOffset, String raw,
                                             Throwable failure) {
        return new ParsedRecord<>(null, ordinal, startOffset, endOffset, endOffset - startOffset, raw,
                failure);
    }

    /**
     * Whether this record parsed.
     *
     * @return {@code true} if {@link #value()} is usable
     */
    public boolean ok() {
        return failure == null;
    }
}
