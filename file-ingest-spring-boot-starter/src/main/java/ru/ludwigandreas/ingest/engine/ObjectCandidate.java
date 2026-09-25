package ru.ludwigandreas.ingest.engine;

import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.api.StoredObject;

/**
 * An object that matched a task's prefix and pattern, with the metadata a run is keyed on.
 *
 * @param uri             where it is
 * @param metadata        its size, etag and version
 * @param expectedRecords the record count its sentinel declared, or {@code null} when there was no
 *                        sentinel or it declared none
 */
public record ObjectCandidate(ObjectUri uri, StoredObject metadata, Long expectedRecords) {

    /**
     * The identity a run is keyed on: the etag, or the version on a versioned bucket.
     *
     * @return the content identity, which is the third column of the run's unique constraint
     */
    public String contentIdentity() {
        return metadata.contentIdentity();
    }
}
