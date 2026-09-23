package ru.ludwigandreas.usersettings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;
import ru.ludwigandreas.usersettings.api.SettingLayer;

/**
 * One stored value, at one scope.
 *
 * <p>The row is keyed on {@code (tenant_id, scope_type, scope_id, setting_key)} and nothing else.
 * That composite is the natural key and is enforced by a unique constraint, which is what makes a
 * write an upsert and a replayed projection event a no-op rather than a duplicate.
 *
 * <p><b>tenant_id is not nullable.</b> A nullable tenant would mean "applies to everyone", and the
 * moment such a row exists, every tenant-scoped query has to be written as
 * {@code tenant = ? OR tenant IS NULL} - which is one forgotten clause away from a cross-tenant
 * read. The deployment-wide layer is configuration instead (see {@link SettingLayer#PLATFORM}), so
 * there is no row here that any tenant's query has to reach for, and the predicate stays a plain
 * equality that is hard to get wrong.
 *
 * <p>{@link #getValueText()} is opaque. Nothing in this module or any consumer queries inside it -
 * there is no index on it, no filter over it and no report from it - which is the condition that
 * makes storing a typed value as text acceptable at all. See {@code SettingValueConverter}.
 */
@Getter
@Setter
@Entity
/*
 * The administrative search surface. OData filtering is a deny-by-default allow-list: a field
 * without @Filterable is unreachable through $filter and $orderby however the query is written.
 *
 * value_text carries no annotation and must never be given one. Whether a value is personal data is
 * a property of its DEFINITION, not of the column - one column holds every setting's value - so
 * there is no per-row rule that could let the safe ones through and hold the flagged ones back. The
 * only enforceable answer is that no setting value is filterable, which is also no real loss:
 * "find users whose timezone is Europe/Moscow" is a reporting question, and this schema is
 * explicitly not built to answer reporting questions.
 */
@FilterPolicy(maxDepth = 3, maxPageSize = 200, defaultPageSize = 50)
@Table(name = "user_setting_value",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_setting_value_scope",
                columnNames = {"tenant_id", "scope_type", "scope_id", "setting_key"}),
        indexes = {
                @Index(name = "idx_user_setting_value_lookup",
                        columnList = "tenant_id, scope_type, scope_id"),
                @Index(name = "idx_user_setting_value_key", columnList = "tenant_id, setting_key")
        })
public class UserSettingValueEntity extends AuditedEntity<UUID> {

    @Filterable
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /**
     * Which layer this row belongs to. Only {@link SettingLayer#USER}, {@link SettingLayer#ROLE} and
     * {@link SettingLayer#TENANT} ever appear; the other two layers are not stored, and a check
     * constraint in the changelog says so at the database rather than trusting every writer.
     */
    @Filterable
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private SettingLayer scopeType;

    /** The subject, the role code or the tenant id, depending on {@link #scopeType}. */
    @Filterable
    @Column(name = "scope_id", nullable = false, length = 255)
    private String scopeId;

    @Filterable
    @Column(name = "setting_key", nullable = false, length = 128)
    private String settingKey;

    /**
     * The value, encoded by the definition's converter. Never interpreted by the database.
     *
     * <p>Null exactly when {@link #isRemoved()} is true, which is the one state a row can be in
     * without holding a value.
     */
    @Column(name = "value_text", columnDefinition = "text")
    private String valueText;

    /** How {@link #valueText} was encoded; see {@code SettingValueTypes}. Null on a tombstone. */
    @Column(name = "value_type", length = 32)
    private String valueType;

    /**
     * A tombstone: the value was reset, and this row records that it was, and when.
     *
     * <p>Removing the row outright would be tidier and is not order-tolerant. A projection consumes
     * an at-least-once, partially-ordered stream, so it can be told "removed at 12:05" before it is
     * told "set at 12:00" - and with no row to compare against, the older set would then be applied
     * and the value would come back from the dead. A tombstone carries the removal's timestamp, so
     * the late set is recognized as stale and dropped like any other out-of-order event.
     *
     * <p>Owner mode writes tombstones too, rather than deleting. It does not need them for ordering,
     * but having one write path instead of two means the projection and the owner cannot drift, and
     * the retention job clears old tombstones on both.
     */
    @Filterable
    @Column(name = "removed", nullable = false)
    private boolean removed;

    /**
     * When this value last changed, as the <em>owner</em> understood it.
     *
     * <p>Distinct from {@code updatedAt}, which is when this database row was written. In owner mode
     * the two coincide; in a projection they do not, and it is this column - carrying the owner's
     * timestamp from the event - that lets a replayed or out-of-order event be recognized as stale
     * and dropped. Comparing {@code updatedAt} instead would compare "when did my replica write"
     * against "when did the change happen", which is never the same question.
     */
    @Filterable
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
}
