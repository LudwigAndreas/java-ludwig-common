package ru.ludwigandreas.fileaction.engine;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/**
 * Where a submission's bytes and artifacts go in the object store.
 *
 * <h2>Why uploads are keyed by date and then by hash</h2>
 *
 * <p>The date prefix is for the operator: a bucket with a year of uploads in one flat prefix is one nobody can
 * list, and {@code object-storage}'s listing is lazy precisely because that happens. The hash is the name
 * because it makes the key derivable from the content - so a re-upload of the same file writes the same object
 * rather than a second copy, and a stored object can always be checked against what the submission row says it
 * is.
 *
 * <p>The date is formatted in UTC, not the caller's zone. This is the one place in the module where the caller's
 * zone is deliberately not used: a key is an operator's index, two callers in different zones uploading the same
 * second must agree on which prefix it went to, and a key that depended on who uploaded it would make an object
 * findable only by someone who knew their zone.
 */
final class ObjectKeys {

    private static final DateTimeFormatter DATE_PREFIX =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private ObjectKeys() {
    }

    /**
     * Where a submitted file goes.
     *
     * @param prefix the configured uploads prefix
     * @param when   when it was submitted
     * @param sha256 the content hash
     * @param format what it is
     * @return the object URI
     */
    static String upload(String prefix, Instant when, String sha256, SourceFormat format) {
        return join(prefix, DATE_PREFIX.format(when) + "/" + sha256 + "." + format.extension());
    }

    /**
     * Where a submission's bound-row artifact goes.
     *
     * @param prefix       the configured artifacts prefix
     * @param submissionId the submission
     * @return the object URI
     */
    static String boundRows(String prefix, UUID submissionId) {
        return join(prefix, submissionId + "/bound.ndjson");
    }

    /**
     * Where a submission's reject report goes.
     *
     * @param prefix       the configured artifacts prefix
     * @param submissionId the submission
     * @param extension    the report's file extension
     * @return the object URI
     */
    static String errorReport(String prefix, UUID submissionId, String extension) {
        return join(prefix, submissionId + "/rejects." + extension);
    }

    private static String join(String prefix, String key) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException(
                    "a storage prefix is not configured; FileActionConfigurationValidator refuses to start"
                            + " without one, so reaching here means it was bypassed");
        }
        String base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return base + "/" + key;
    }
}
