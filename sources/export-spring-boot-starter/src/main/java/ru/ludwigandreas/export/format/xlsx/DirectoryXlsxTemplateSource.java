package ru.ludwigandreas.export.format.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

/**
 * Resolves a template name against a configured directory, then against the classpath.
 *
 * <h2>Why it re-reads per run instead of watching the directory</h2>
 *
 * <p>The requirement is that a branding change reaches production without a deploy. Re-reading the
 * file each time a report starts satisfies it exactly, and it is what this class does.
 *
 * <p>The alternative considered was {@code hot-reload-spring-boot-starter}'s watcher, which is what
 * the FreeMarker templates use. It is the right mechanism there and the wrong one here, and the
 * difference is how often the thing is read. A notification template is rendered on the hot path,
 * thousands of times a minute, so a cache with a watcher in front of it pays for itself. A report
 * template is read once per run - a run that then spends minutes walking a million rows - so a
 * cache would save a few milliseconds out of several minutes, and would buy in exchange a second
 * source of truth that can be stale, a watcher thread, and a class of bug where the file on disk
 * and the file in the workbook disagree. Reading it every time is both simpler and strictly more
 * correct.
 *
 * <h2>What a name may resolve to</h2>
 *
 * <p>The name is resolved under the configured directory and rejected if it escapes it, and only
 * then against a fixed classpath prefix. A template names the letterhead of a document that leaves
 * the organisation; without those two bounds, a definition - or anything that could influence one -
 * would be able to read an arbitrary file and ship it to whoever downloads the report.
 */
@Slf4j
public class DirectoryXlsxTemplateSource implements XlsxTemplateSource {

    /** Where a classpath template lives, so a name can never reach outside it. */
    static final String CLASSPATH_PREFIX = "export-templates/";

    private static final String EXTENSION = ".xlsx";

    private final Path directory;

    /**
     * Creates the source.
     *
     * @param directory the configured template directory, or null to use the classpath only
     */
    public DirectoryXlsxTemplateSource(Path directory) {
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    @Override
    public Optional<InputStream> open(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<InputStream> fromDirectory = fromDirectory(name);
        if (fromDirectory.isPresent()) {
            return fromDirectory;
        }
        ClassPathResource resource = new ClassPathResource(CLASSPATH_PREFIX + fileName(name));
        if (resource.exists()) {
            return Optional.of(resource.getInputStream());
        }
        log.warn("Report template '{}' was not found in {} or on the classpath under {}; the report"
                + " will be written without branding", name, directory, CLASSPATH_PREFIX);
        return Optional.empty();
    }

    private Optional<InputStream> fromDirectory(String name) throws IOException {
        if (directory == null) {
            return Optional.empty();
        }
        Path candidate;
        try {
            candidate = directory.resolve(fileName(name)).normalize();
        } catch (InvalidPathException e) {
            log.warn("Report template name '{}' is not a valid file name: {}", name, e.toString());
            return Optional.empty();
        }
        if (!candidate.startsWith(directory)) {
            log.warn("Report template name '{}' escapes the configured template directory; ignored", name);
            return Optional.empty();
        }
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(Files.newInputStream(candidate));
    }

    private String fileName(String name) {
        return name.endsWith(EXTENSION) ? name : name + EXTENSION;
    }
}
