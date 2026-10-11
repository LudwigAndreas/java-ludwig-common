package ru.ludwigandreas.reconciliation.integration.testmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.util.UUID;

/**
 * A consuming service's own copy of a partner item.
 *
 * <p>{@code removed} is a plain flag rather than a filtered soft delete on purpose: an upsert has to
 * be able to find a row it flagged earlier in order to restore it, which is exactly what a restriction
 * that hides removed rows prevents.
 */
@Getter
@Setter
@Entity
@Table(name = "local_item")
public class LocalItem extends GeneratedEntity<UUID> {

    /** A leaf item mirrored under a scope. */
    public static final String KIND_ITEM = "ITEM";

    /** A page of the self-similar tree, which is both a member and a scope. */
    public static final String KIND_PAGE = "PAGE";

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(name = "kind", nullable = false)
    private String kind = KIND_ITEM;

    @Column(name = "parent")
    private String parent;

    @Column(name = "title")
    private String title;

    @Column(name = "removed", nullable = false)
    private boolean removed;

    @Column(name = "sync_count", nullable = false)
    private int syncCount;
}
