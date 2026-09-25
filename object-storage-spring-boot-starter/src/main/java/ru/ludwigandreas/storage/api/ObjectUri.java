package ru.ludwigandreas.storage.api;

import java.nio.file.Path;
import java.util.Locale;
import ru.ludwigandreas.storage.exception.InvalidObjectUriException;

/**
 * The one place a storage location is parsed, and the reason every {@link ObjectStore} method takes
 * a single string rather than a bucket and a key.
 *
 * <h2>Why one string</h2>
 *
 * <p>An interface whose methods take {@code (bucket, key)} forces every call site to know which
 * bucket it is talking to. That sounds harmless until the bucket becomes configuration: the name
 * then has to be threaded from properties through every service, every job and every test fixture
 * that wants to read a file, and the one place it is forgotten silently reads from the default. A
 * single {@code s3://bucket/key} keeps the location whole, so it can be logged, stored in a database
 * column as the identity of a run's source, compared for equality and handed back to the store later
 * without reassembly.
 *
 * <p>It also keeps the choice of implementation out of the caller. {@code file:///var/drop/x.csv}
 * and {@code s3://drop/x.csv} are both locations; which store serves them is a routing decision made
 * once, in {@code ObjectStoreRouter}, rather than a branch at every call site.
 *
 * <h2>The two schemes, and what "key" means in each</h2>
 *
 * <ul>
 *   <li>{@code s3://bucket/some/key} - {@link #container()} is the bucket, {@link #key()} is the
 *       key with no leading slash, which is how S3 itself names it. A key is not a path: it has no
 *       {@code .} or {@code ..} semantics and the slashes in it are ordinary characters that only
 *       {@code list} treats as a hierarchy.</li>
 *   <li>{@code file:///var/drop/x.csv} - {@link #container()} is empty and {@link #key()} is the
 *       absolute path. The three slashes are not a typo: {@code file://} takes an authority, and an
 *       empty authority followed by an absolute path is what an RFC 8089 local file URI looks
 *       like.</li>
 * </ul>
 *
 * <h2>What this type refuses</h2>
 *
 * <p>A bucket-only {@code s3://bucket} is accepted and carries an empty key, because that is a legal
 * argument to {@code list} - "everything in the bucket". Everything else that could be ambiguous is
 * rejected at parse time rather than at the point it would have done damage: an unknown scheme, a
 * missing scheme (a bare path is a common mistake and would otherwise be read as a relative file),
 * a key containing a {@code ..} segment, and for {@code file:} a relative path. The {@code ..} check
 * matters most for the filesystem store, where a key is resolved against a root and a traversal
 * would escape it; it is applied to both schemes so that a key which is valid for one store cannot
 * become dangerous by being routed to the other.
 *
 * @param scheme    the lower-case scheme, one of {@link #SCHEME_S3} or {@link #SCHEME_FILE}
 * @param container the bucket for {@code s3:}, empty for {@code file:}
 * @param key       the key, or the absolute path for {@code file:}; never starts with a slash for
 *                  {@code s3:} and always does for {@code file:}
 */
public record ObjectUri(String scheme, String container, String key) {

    /** Object storage speaking the S3 API: AWS S3, MinIO, Ceph RGW, anything else with the same API. */
    public static final String SCHEME_S3 = "s3";

    /** A directory on this machine. */
    public static final String SCHEME_FILE = "file";

    private static final String S3_PREFIX = SCHEME_S3 + "://";
    private static final String FILE_PREFIX = SCHEME_FILE + "://";
    private static final String PARENT_SEGMENT = "..";

    /**
     * Validates the parts.
     *
     * @throws InvalidObjectUriException if any part is inconsistent with the scheme
     */
    public ObjectUri {
        if (scheme == null || container == null || key == null) {
            throw new InvalidObjectUriException(String.valueOf(scheme) + "://" + container + "/" + key);
        }
        if (!SCHEME_S3.equals(scheme) && !SCHEME_FILE.equals(scheme)) {
            throw new InvalidObjectUriException(scheme + "://" + container + "/" + key);
        }
        if (SCHEME_S3.equals(scheme) && container.isBlank()) {
            throw new InvalidObjectUriException(S3_PREFIX + container + "/" + key);
        }
        if (hasParentSegment(key)) {
            throw new InvalidObjectUriException(scheme + "://" + container + "/" + key);
        }
    }

    /**
     * Parses a location.
     *
     * @param uri the location, for example {@code s3://drop/catalogue/2026-09-25.csv.gz}
     * @return the parsed location
     * @throws InvalidObjectUriException if it is blank, carries no scheme, carries a scheme this
     *                                   platform does not serve, or contains a {@code ..} segment
     */
    public static ObjectUri parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new InvalidObjectUriException(String.valueOf(uri));
        }
        String trimmed = uri.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(S3_PREFIX)) {
            String rest = trimmed.substring(S3_PREFIX.length());
            int slash = rest.indexOf('/');
            String bucket = slash < 0 ? rest : rest.substring(0, slash);
            String key = slash < 0 ? "" : rest.substring(slash + 1);
            return new ObjectUri(SCHEME_S3, bucket, key);
        }
        if (lower.startsWith(FILE_PREFIX)) {
            String path = trimmed.substring(FILE_PREFIX.length());
            // An authority is allowed by the syntax and meaningless here: file://host/path names a
            // file on another machine, which this module has no way to reach. Rejecting it is better
            // than silently reading the local path of the same name.
            if (!path.startsWith("/")) {
                throw new InvalidObjectUriException(trimmed);
            }
            return new ObjectUri(SCHEME_FILE, "", path);
        }
        throw new InvalidObjectUriException(trimmed);
    }

    /**
     * The location of a local file, for a caller holding a {@link Path} rather than a string.
     *
     * @param path the file; relative paths are resolved against the working directory first, because
     *             a relative {@code file:} URI has no defined meaning
     * @return the location
     */
    public static ObjectUri ofFile(Path path) {
        return new ObjectUri(SCHEME_FILE, "", path.toAbsolutePath().normalize().toString());
    }

    /**
     * The location of an S3 object.
     *
     * @param bucket the bucket
     * @param key    the key, with or without a leading slash
     * @return the location
     */
    public static ObjectUri ofS3(String bucket, String key) {
        String normalized = key == null ? "" : key;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return new ObjectUri(SCHEME_S3, bucket, normalized);
    }

    /**
     * Whether this location names an S3 object.
     *
     * @return {@code true} for {@code s3:}
     */
    public boolean isS3() {
        return SCHEME_S3.equals(scheme);
    }

    /**
     * A sibling location under the same container.
     *
     * <p>Used wherever a key is derived from another one - a sentinel file next to the data file, an
     * archived copy under a different prefix - so that the derivation never goes through string
     * concatenation at the call site and never loses the bucket.
     *
     * @param newKey the new key, or absolute path for {@code file:}
     * @return the sibling location
     */
    public ObjectUri withKey(String newKey) {
        return new ObjectUri(scheme, container, newKey);
    }

    /**
     * The last slash-separated segment of the key, which is what a human calls the file name.
     *
     * @return the name, or an empty string for a container-level or directory-like location
     */
    public String name() {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /**
     * Renders the location back into the string form it was parsed from.
     *
     * <p>Canonical: parsing this string yields an equal {@code ObjectUri}, and two locations that
     * name the same object render identically even when they were written differently
     * ({@code s3://b} and {@code s3://b/} both render as {@code s3://b}). That is what lets a
     * location be stored in a database column, used in a unique constraint and compared as text.
     *
     * @return the canonical string form
     */
    public String value() {
        if (!isS3()) {
            return FILE_PREFIX + key;
        }
        return key.isEmpty() ? S3_PREFIX + container : S3_PREFIX + container + "/" + key;
    }

    @Override
    public String toString() {
        return value();
    }

    private static boolean hasParentSegment(String key) {
        for (String segment : key.split("/", -1)) {
            if (PARENT_SEGMENT.equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
