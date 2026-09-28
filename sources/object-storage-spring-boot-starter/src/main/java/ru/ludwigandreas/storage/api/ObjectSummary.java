package ru.ludwigandreas.storage.api;

import java.time.Instant;

/**
 * One entry in a listing.
 *
 * <p>Deliberately smaller than {@link StoredObject}: a listing returns what the store's list
 * operation returns, and S3's {@code ListObjectsV2} carries no content type and no version. A
 * summary that pretended to carry them would have to fetch each object's metadata individually,
 * turning a listing of ten thousand objects into ten thousand extra round trips - which is precisely
 * the behaviour a lazily paged listing exists to avoid. A caller that needs the full metadata for one
 * entry calls {@link ObjectStore#head(String)} on it, and does so for the few it cares about rather
 * than for all of them.
 *
 * @param uri          where the object is, in canonical form
 * @param size         its length in bytes
 * @param etag         the store's content identifier, without surrounding quotes
 * @param lastModified when the store says it was last written
 */
public record ObjectSummary(String uri, long size, String etag, Instant lastModified) {

    /**
     * The last slash-separated segment of the key, which is what a pattern like
     * {@code catalogue-*.csv.gz} is matched against.
     *
     * @return the file name part of the location
     */
    public String name() {
        return ObjectUri.parse(uri).name();
    }
}
