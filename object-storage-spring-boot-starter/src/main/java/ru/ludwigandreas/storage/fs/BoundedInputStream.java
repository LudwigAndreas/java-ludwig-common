package ru.ludwigandreas.storage.fs;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stops a stream after a fixed number of bytes.
 *
 * <p>What makes a bounded ranged read on a local file behave like one over HTTP. A
 * {@link java.nio.channels.SeekableByteChannel} positioned at the range start will happily read to
 * the end of the file; S3, given the same range, stops at the last byte the caller asked for. Without
 * this the two implementations would disagree on every bounded range, and the disagreement would be
 * invisible in any test that only ever asked for a range ending at EOF - which is the range a resume
 * uses, so the tests most likely to be written would be the ones least likely to catch it.
 *
 * <p>Deliberately not {@code commons-io}'s class of the same name. This module has no other reason
 * to depend on commons-io, and adding one to a starter every service will resolve - in a reactor
 * where the commons-io version is already a pinned convergence compromise between POI and Olingo -
 * costs more than the thirty lines below.
 *
 * <p>Closing this closes the stream underneath it, which is what releases the channel.
 */
final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    /**
     * Wraps a stream so that it ends after a fixed number of bytes.
     *
     * @param in    the stream to bound
     * @param limit how many bytes may be read from it
     */
    BoundedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int value = super.read();
        if (value >= 0) {
            remaining--;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int allowed = (int) Math.min(length, remaining);
        int read = super.read(buffer, offset, allowed);
        if (read > 0) {
            remaining -= read;
        }
        return read;
    }

    @Override
    public long skip(long count) throws IOException {
        long skipped = super.skip(Math.min(count, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), remaining);
    }

    @Override
    public boolean markSupported() {
        // Marking would have to remember the remaining count as well as the underlying position, and
        // nothing in this module rewinds a ranged read - a resume re-opens at a new offset instead.
        // Saying so is better than inheriting a mark that silently loses the bound on reset.
        return false;
    }
}
