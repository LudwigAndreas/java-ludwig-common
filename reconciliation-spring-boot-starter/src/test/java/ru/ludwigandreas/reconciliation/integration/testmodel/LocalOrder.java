package ru.ludwigandreas.reconciliation.integration.testmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * The local record the tests keep in sync: an order whose status is owned by a billing partner.
 *
 * <p>Extends {@code AuditedEntity}, so it carries the optimistic-locking version column the module's
 * stale-write protection relies on - the fixture has to have the same shape as a real one for the
 * guarantees under test to mean anything.
 */
@Getter
@Setter
@Entity
@Table(name = "local_order")
public class LocalOrder extends AuditedEntity<UUID> {

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    /** What the partner said the last time this order was reconciled, for the stale-write assertions. */
    @Column(name = "source_timestamp")
    private Instant sourceTimestamp;

    @Column(name = "sync_count", nullable = false)
    private int syncCount;
}
