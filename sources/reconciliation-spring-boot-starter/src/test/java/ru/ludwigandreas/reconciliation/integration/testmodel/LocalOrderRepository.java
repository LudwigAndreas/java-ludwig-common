package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.db.core.repository.BaseRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository for the test's local records. */
public interface LocalOrderRepository extends BaseRepository<LocalOrder, UUID> {

    /** Finds an order by the partner's id. */
    Optional<LocalOrder> findByExternalId(String externalId);

    /** The orders that are not in a terminal status - the hot half of demand. */
    List<LocalOrder> findByStatusNot(String status);
}
