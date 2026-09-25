package ru.ludwigandreas.storage.api;

import java.util.Map;

/**
 * What to record alongside an object's bytes.
 *
 * <p>A record with a factory rather than a builder with twenty fields: the SDK already has the
 * builder, and a wrapper that exposed every S3 request field would make the interface unimplementable
 * by the filesystem store and would tie every caller to S3's vocabulary. What is here is what both
 * implementations can honour and what a caller actually sets.
 *
 * <p>User metadata is stored verbatim. It is not a place to put anything sensitive: on S3 it travels
 * in response headers and is visible to anyone who can {@code HeadObject} the key, which is a wider
 * set of principals than those who can read the body on a bucket with object-level policies.
 *
 * @param contentType  the content type to record, or {@code null} to let the store decide
 * @param userMetadata arbitrary key/value pairs stored with the object; never null
 */
public record PutOptions(String contentType, Map<String, String> userMetadata) {

    private static final PutOptions NONE = new PutOptions(null, Map.of());

    /**
     * Normalises the metadata map so that callers and implementations never see null.
     */
    public PutOptions {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    /**
     * No content type, no metadata.
     *
     * @return the empty options
     */
    public static PutOptions none() {
        return NONE;
    }

    /**
     * Only a content type.
     *
     * @param contentType the content type to record
     * @return the options
     */
    public static PutOptions ofContentType(String contentType) {
        return new PutOptions(contentType, Map.of());
    }
}
