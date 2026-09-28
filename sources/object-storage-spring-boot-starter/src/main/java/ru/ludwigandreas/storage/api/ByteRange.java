package ru.ludwigandreas.storage.api;

/**
 * A half-open-at-the-end window over an object's bytes, expressed the way HTTP expresses it.
 *
 * <h2>Why this type exists rather than two long parameters</h2>
 *
 * <p>Ranges are the one part of this API that is easy to get subtly wrong and impossible to notice:
 * HTTP's {@code Range} header is <em>inclusive</em> at both ends, so the first 10 bytes of an object
 * are {@code bytes=0-9}, while every stream and buffer API in Java is exclusive at the end. Passing
 * {@code (0, 10)} to a method taking two longs is correct under one convention and off by one under
 * the other, and the symptom is a single duplicated or missing record in the middle of a file that
 * otherwise imports cleanly. Making the window a type means the conversion happens once, here, with
 * a name on it.
 *
 * <h2>The unbounded case is the common one</h2>
 *
 * <p>Resuming an interrupted read means "from byte 800000000 to the end", and the end is not known
 * to the caller - that is what it is resuming from a checkpoint rather than from a plan. A range
 * therefore carries {@link #TO_END} as its end rather than requiring the caller to {@code head} the
 * object first, which would be a second round trip whose answer can change between the two calls.
 *
 * <h2>A range that starts at or past the end is empty, not an error</h2>
 *
 * <p>S3 answers such a request with {@code 416 Range Not Satisfiable}; a {@code SeekableByteChannel}
 * positioned past the end simply reads {@code -1}. The two have to agree or a resume test would pass
 * against one implementation and fail against the other, so {@code ObjectStore} defines the outcome
 * as an empty stream and each implementation is responsible for producing it. This is not a
 * convenience: a run that is resumed after having consumed the whole object - which is exactly what
 * a crash between the last batch and the run being marked complete produces - asks for precisely
 * this range, and it has to read zero records rather than fail.
 *
 * @param start        the first byte to read, zero-based and never negative
 * @param endInclusive the last byte to read, inclusive, or {@link #TO_END} for "as far as it goes"
 */
public record ByteRange(long start, long endInclusive) {

    /** The {@code endInclusive} value meaning "to the end of the object, however long it is". */
    public static final long TO_END = -1L;

    /**
     * Validates the window.
     *
     * @throws IllegalArgumentException if the start is negative, or the end precedes the start
     */
    public ByteRange {
        if (start < 0) {
            throw new IllegalArgumentException("A byte range cannot start before byte 0: " + start);
        }
        if (endInclusive != TO_END && endInclusive < start) {
            throw new IllegalArgumentException(
                    "A byte range cannot end before it starts: " + start + ".." + endInclusive);
        }
    }

    /**
     * Everything from {@code start} to the end of the object.
     *
     * @param start the first byte to read
     * @return the range
     */
    public static ByteRange from(long start) {
        return new ByteRange(start, TO_END);
    }

    /**
     * An inclusive window, in the same convention HTTP uses.
     *
     * @param start        the first byte to read
     * @param endInclusive the last byte to read
     * @return the range
     */
    public static ByteRange of(long start, long endInclusive) {
        return new ByteRange(start, endInclusive);
    }

    /**
     * A window of a given length, in the exclusive-end convention the rest of Java uses.
     *
     * <p>The reason both factories exist: a caller that has an offset and a count should not have to
     * remember to subtract one, and a caller that is reproducing a {@code Range} header should not
     * have to remember to add one.
     *
     * <p>A length of zero is rejected rather than represented. HTTP has no syntax for an empty
     * range, an inclusive end cannot express one, and a caller asking for nothing is a bug in the
     * caller's arithmetic rather than a request the store should turn into a round trip.
     *
     * @param start  the first byte to read
     * @param length how many bytes, at least one
     * @return the range
     * @throws IllegalArgumentException if the length is not positive
     */
    public static ByteRange ofLength(long start, long length) {
        if (length <= 0) {
            throw new IllegalArgumentException("A byte range needs a positive length, was: " + length);
        }
        return new ByteRange(start, start + length - 1);
    }

    /**
     * Whether this range names its own end.
     *
     * @return {@code true} if an end byte was given, {@code false} for a read to the end of the object
     */
    public boolean bounded() {
        return endInclusive != TO_END;
    }

    /**
     * How many bytes this range covers, when it is bounded.
     *
     * @return the length in bytes
     * @throws IllegalStateException if the range runs to the end of the object, whose length is not
     *                               known here
     */
    public long length() {
        if (!bounded()) {
            throw new IllegalStateException("An unbounded byte range has no length until the object is read");
        }
        return endInclusive - start + 1;
    }

    /**
     * Renders the range as the value of an HTTP {@code Range} header.
     *
     * <p>The one place the inclusive-end convention is written down, which is why no implementation
     * builds this string itself.
     *
     * @return {@code bytes=start-end}, or {@code bytes=start-} for an unbounded range
     */
    public String toHeaderValue() {
        return bounded() ? "bytes=" + start + "-" + endInclusive : "bytes=" + start + "-";
    }
}
