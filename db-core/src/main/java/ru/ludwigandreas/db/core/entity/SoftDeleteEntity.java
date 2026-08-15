package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * Adds {@code deleted}/{@code deletedAt} marker columns. A {@code @MappedSuperclass} cannot itself carry
 * Hibernate's {@code @SQLDelete}/{@code @SQLRestriction} annotations, since those need the literal table name
 * of the concrete entity, so each concrete {@code @Entity} extending this class must add them itself, e.g.:
 * <pre>{@code
 * @Entity
 * @Table(name = "orders")
 * @SQLDelete(sql = "UPDATE orders SET deleted = true, deleted_at = now() WHERE id = ?")
 * @SQLRestriction("deleted = false")
 * public class Order extends SoftDeleteEntity<UUID> { ... }
 * }</pre>
 * With those in place, {@code repository.delete(entity)} becomes an {@code UPDATE} and deleted rows are
 * transparently excluded from normal finder/query methods (native queries bypass the restriction).
 */
@Getter
@Setter
@MappedSuperclass
public abstract class SoftDeleteEntity<ID extends Serializable> extends JpaBaseEntity<ID> {

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
