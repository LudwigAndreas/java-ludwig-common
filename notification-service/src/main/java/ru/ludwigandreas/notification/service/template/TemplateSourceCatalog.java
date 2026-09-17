package ru.ludwigandreas.notification.service.template;

import freemarker.cache.TemplateLoader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Reads a template's raw source and hashes it, which is what makes a sent message traceable to exact
 * text after the file has been edited.
 *
 * <p>FreeMarker's {@code Template} does not expose the source it was parsed from, so the source is
 * read through the same {@link TemplateLoader} the {@code Configuration} uses - the hot-reloadable
 * one - rather than through the file system directly. That matters: a service configured to load
 * templates from the classpath has no directory to read, and going behind the loader would work on a
 * developer's machine and fail in the container.
 *
 * <h2>Deliberately not cached</h2>
 *
 * <p>The obvious optimization is to cache the source and its hash, keyed by the template name and its
 * last-modified stamp. That is wrong, and subtly: file modification times are not
 * millisecond-precise everywhere - HFS+ reports whole seconds, and several network filesystems are
 * coarser still - so two edits inside one granularity window produce the same key. The cache would
 * then serve the <em>previous</em> text, and because the render is driven from the same read, the
 * service would quietly go on sending the old wording until somebody edited the file a third time.
 * A hot-reload mechanism whose failure mode is "your change appeared not to take" is worse than no
 * hot reload at all, because nobody believes it the next time either.
 *
 * <p>What is given up is small. A render reads at most three files and hashes a few kilobytes, on a
 * path whose next step is a network round trip to a mail relay measured in tens of milliseconds - and
 * the files are in the page cache. Correctness here costs microseconds; the cache saved microseconds
 * and risked sending the wrong text.
 *
 * <p>Reading the source is also what keeps the hash honest: the bytes that are hashed are the same
 * bytes that are parsed and rendered, so it is impossible for a recorded version to describe text
 * other than the text that went out.
 */
public class TemplateSourceCatalog {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** Read buffer for pulling a template's source out of its loader. */
    private static final int BUFFER_SIZE = 4096;

    private final TemplateLoader templateLoader;

    public TemplateSourceCatalog(TemplateLoader templateLoader) {
        this.templateLoader = templateLoader;
    }

    /**
     * Whether a template exists, without reading it.
     *
     * <p>Used where only existence matters, so probing the fallback chain does not read files it is
     * about to discard.
     */
    public boolean exists(String templateName) {
        try {
            Object source = templateLoader.findTemplateSource(templateName);
            if (source == null) {
                return false;
            }
            templateLoader.closeTemplateSource(source);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not probe template " + templateName, e);
        }
    }

    /** The template's source and its hash, or empty when the template does not exist. */
    public Optional<TemplateSource> load(String templateName) {
        try {
            Object handle = templateLoader.findTemplateSource(templateName);
            if (handle == null) {
                return Optional.empty();
            }
            try {
                String text = read(handle);
                return Optional.of(new TemplateSource(templateName, text, hash(text)));
            } finally {
                templateLoader.closeTemplateSource(handle);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read template " + templateName, e);
        }
    }

    private String read(Object handle) throws IOException {
        try (Reader reader = templateLoader.getReader(handle, StandardCharsets.UTF_8.name())) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[BUFFER_SIZE];
            int read = reader.read(buffer);
            while (read >= 0) {
                text.append(buffer, 0, read);
                read = reader.read(buffer);
            }
            return text.toString();
        }
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK specification, so this cannot happen on a conformant
            // runtime; rethrowing unchecked keeps the caller free of a branch that cannot be taken.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is required but unavailable", e);
        }
    }

    /** One template's text and the hex SHA-256 of it. */
    public record TemplateSource(String name, String text, String contentHash) {
    }
}
