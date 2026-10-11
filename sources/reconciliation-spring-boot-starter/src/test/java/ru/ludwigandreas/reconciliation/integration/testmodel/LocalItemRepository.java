package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.db.core.repository.BaseRepository;

import java.util.Optional;
import java.util.UUID;

/** The test application's member table. */
public interface LocalItemRepository extends BaseRepository<LocalItem, UUID> {

    /**
     * One item by the partner's id.
     *
     * @param externalId the partner's id
     * @return the item, removed or not
     */
    default Optional<LocalItem> byExternalId(String externalId) {
        return findAll().stream().filter(item -> item.getExternalId().equals(externalId)).findFirst();
    }
}
