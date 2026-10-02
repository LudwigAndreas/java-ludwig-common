package ru.ludwigandreas.fileaction.engine;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens the bytes of a submission.
 *
 * <h2>Why the engine opens the stream rather than being handed one</h2>
 *
 * <p>Because {@code MultipartFile.getInputStream} throws a checked exception, and somebody has to deal with it.
 * The web layer is the wrong somebody: {@code exceptions.controllers-do-not-catch-checked-exceptions} covers the
 * whole {@code web} package, and the rule is right about why - a request that cannot be read is not a decision an
 * endpoint should be taking inline, and the engine already has the {@code IOException} handling that every other
 * read in this module goes through.
 *
 * <p>So the controller passes {@code file::getInputStream} - a method reference, which catches nothing - and the
 * engine opens it where it opens everything else. An earlier version put a converting helper in the web package
 * instead, which satisfied nobody: it moved the catch one class sideways and the rule, correctly, still saw it.
 */
@FunctionalInterface
public interface ContentSource {

    /**
     * Opens the content.
     *
     * <p>Called once, and the caller closes what it returns.
     *
     * @return the bytes, positioned at the beginning
     * @throws IOException if they cannot be read
     */
    InputStream open() throws IOException;
}
