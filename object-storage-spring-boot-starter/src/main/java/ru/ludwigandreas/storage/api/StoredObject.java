package ru.ludwigandreas.storage.api;

import java.time.Instant;

/**
 * What a store knows about one object without reading it.
 *
 * <h2>Why the version matters as much as the etag</h2>
 *
 * <p>An etag identifies content, and that is what makes it the right half of an exactly-once
 * identity for an ingest: a partner who re-uploads a corrected file under the same name produces a
 * different etag and therefore a different run, while a re-offered identical file produces the same
 * one and is skipped. But an etag is not a promise across implementations - S3 computes it as a
 * plain MD5 for a single-part upload and as a hash-of-hashes with a part suffix for a multipart one,
 * so the same bytes uploaded two ways carry two etags. A caller that needs a stable identity on a
 * versioned bucket should prefer {@link #versionId()}, which the store fills in when the bucket has
 * versioning switched on and leaves null when it does not.
 *
 * <p>Both are carried rather than one being normalised into the other, because collapsing them would
 * hide exactly the case where they disagree, which is the case that matters. {@link #contentIdentity()}
 * is the accessor to use when what is wanted is "the strongest identity this store offers", so that
 * the choice between them is made once here rather than at every call site.
 *
 * <p>{@code versionId} and {@code contentType} are nullable rather than {@code Optional}, which is
 * this repository's convention for a field: {@code architecture-rules} enforces it, on the grounds
 * that an {@code Optional} field can still be null and so buys a wrapper without buying the
 * guarantee. Callers that want one write {@code Optional.ofNullable(head.versionId())}.
 *
 * @param uri          where the object is, in the canonical form {@link ObjectUri#value()} produces
 * @param size         the object's length in bytes
 * @param etag         the store's content identifier, without surrounding quotes; never null, and
 *                     empty only for a store that has none
 * @param versionId    the object version on a versioned bucket, null otherwise
 * @param lastModified when the store says it was last written
 * @param contentType  the recorded content type, null when none was stored
 */
public record StoredObject(String uri, long size, String etag, String versionId,
                           Instant lastModified, String contentType) {

    /**
     * Validates the metadata and normalises the etag.
     *
     * @throws IllegalArgumentException if the size is negative
     */
    public StoredObject {
        if (size < 0) {
            throw new IllegalArgumentException("An object cannot have a negative size: " + size);
        }
        // S3 returns the etag quoted, because HTTP does. Stripping the quotes once, here, is what
        // stops a comparison between a stored etag and a freshly read one from failing on punctuation
        // - which is the kind of bug that presents as "the ingest re-imported a file it had already
        // seen" and is found nowhere near this class.
        etag = etag == null ? "" : unquote(etag);
    }

    /**
     * The identity a caller should key on when deciding whether it has already processed this object.
     *
     * <p>The version when there is one, the etag otherwise. Deliberately a single accessor rather
     * than a decision each caller makes for itself: the whole point of preferring the version is that
     * it is stable where the etag is not, and a caller that picked the etag because it happened to be
     * the field in front of it would reintroduce the instability the version exists to remove.
     *
     * @return the strongest content identity this store offers for the object
     */
    public String contentIdentity() {
        return versionId == null || versionId.isBlank() ? etag : versionId;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
