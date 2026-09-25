package ru.ludwigandreas.ingest.engine;

import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.exception.ObjectStoreException;

/**
 * Moves or copies the source object out of the way after a successful run.
 *
 * <h2>Archival is about the partner's bucket, not about correctness</h2>
 *
 * <p>It is off by default, and that is not an oversight. The identity constraint already makes
 * re-offering the same object a no-op, so nothing about correctness depends on the object being moved.
 * What archival is for is a drop prefix that would otherwise accumulate a year of files and make every
 * morning's listing longer than the last - which matters, and is a housekeeping decision the estate
 * owning the bucket should take deliberately.
 *
 * <h2>It runs last, and a failure does not fail the run</h2>
 *
 * <p>After {@code COMPLETED} and after the receipt, for the reason {@code IngestRunner} gives: the
 * database is the source of truth, and a bucket that says "processed" in front of a database that says
 * the run never happened is the one state from which nothing can recover on its own. A failure here is
 * logged, the run stays complete, and the next pass finds an object it has already ingested and skips
 * it - which is the same outcome archival was aiming for, reached more slowly.
 *
 * <p>Both operations are idempotent, which is what lets a re-run redo them safely: copying an object
 * over itself succeeds, and deleting something already absent succeeds by {@code ObjectStore}'s own
 * contract.
 */
@Slf4j
public class SourceArchiver {

    private final ObjectStore store;

    /**
     * Creates the archiver.
     *
     * @param store where the objects are
     */
    public SourceArchiver(ObjectStore store) {
        this.store = store;
    }

    /**
     * Archives the source, if the task says to.
     *
     * @param task      the task
     * @param candidate the object that was ingested
     * @return {@code true} if the object was archived
     */
    public boolean archive(RegisteredIngestTask task, ObjectCandidate candidate) {
        FileIngestProperties.Archive archive = task.settings().getArchive();
        if (archive.getMode() == FileIngestProperties.ArchiveMode.NONE) {
            return false;
        }
        ObjectUri source = candidate.uri();
        ObjectUri target = IngestNaming.archivedFor(source, archive.getPrefix());
        try {
            // copy then delete, never a move: there is no atomic move in an object store, and doing
            // it in this order means an interruption leaves the object in both places rather than in
            // neither. Two copies are a housekeeping problem; none is data loss.
            store.copy(source.value(), target.value());
            if (archive.getMode() == FileIngestProperties.ArchiveMode.MOVE) {
                store.delete(source.value());
            }
            log.info("Archived {} to {}", source.value(), target.value());
            return true;
        } catch (ObjectStoreException e) {
            log.warn("Could not archive {} to {}: {}. The run stays COMPLETED; the object will be"
                    + " recognised as already ingested on the next pass.", source.value(),
                    target.value(), e.toString());
            return false;
        }
    }

}
