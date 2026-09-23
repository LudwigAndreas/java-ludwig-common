package ru.ludwigandreas.usersettings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;
import ru.ludwigandreas.usersettings.api.SettingLayer;

/**
 * One change to one setting: who, when, which setting, what it was, what it became, and at which
 * layer.
 *
 * <p>Written on every write and on every administrative read of somebody else's settings. The read
 * entries look redundant until the first time someone asks who looked at a user's data, at which
 * point they are the only record that could answer.
 *
 * <p><b>Values are redacted for PII-flagged definitions.</b> Not hashed, not truncated - replaced
 * with a marker. An audit trail is a copy of the data it audits, held longer than the data itself
 * and read by more people, so a personal value written here outlives every erasure request that was
 * meant to remove it. What the trail keeps for a flagged setting is that the value changed, when,
 * and by whom, which is what an auditor actually asks.
 *
 * <p>Append-only by convention and by there being no code path that updates a row. Not a
 * {@code SnapshotEntity}: that family models data imported from an outer system and carries the
 * provenance columns to match, none of which mean anything for a record this service authored
 * itself. Consents, which genuinely are evidence, do use it.
 */
@Getter
@Setter
@Entity
/*
 * old_value and new_value carry no @Filterable and must not be given one. They already hold the
 * redaction marker for a PII-flagged setting, but the values of settings that are NOT flagged are
 * stored verbatim, and making them filterable would let an administrator ask "which users have ever
 * had value X" - a question this trail exists to record, not to answer in bulk.
 */
@FilterPolicy(maxDepth = 3, maxPageSize = 200, defaultPageSize = 50)
@Table(name = "user_setting_audit",
        indexes = {
                @Index(name = "idx_user_setting_audit_subject",
                        columnList = "tenant_id, subject, occurred_at"),
                @Index(name = "idx_user_setting_audit_occurred", columnList = "occurred_at")
        })
public class UserSettingAuditEntity extends GeneratedEntity<UUID> {

    @Filterable
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    /** Whose setting changed, or whose settings were read. */
    @Filterable
    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    /** Who made the change or performed the read. The same as {@link #subject} for a self-service edit. */
    @Filterable
    @Column(name = "actor", nullable = false, length = 255)
    private String actor;

    @Filterable
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private SettingAuditAction action;

    /** Null for a whole-profile read, which names no single setting. */
    @Filterable
    @Column(name = "setting_key", length = 128)
    private String settingKey;

    @Filterable
    @Column(name = "category", length = 64)
    private String category;

    @Filterable
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", length = 32)
    private SettingLayer scopeType;

    @Filterable
    @Column(name = "scope_id", length = 255)
    private String scopeId;

    /** The previous value, or the redaction marker. Null when there was no previous value. */
    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    /** The new value, or the redaction marker. Null for a reset. */
    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    /** Whether the values above are redacted, so a reader is never left guessing what a marker means. */
    @Column(name = "redacted", nullable = false)
    private boolean redacted;

    /**
     * The request's correlation id, when one was bound. This is what joins an audit row to the log
     * lines, traces and downstream calls of the same request - without it, "who changed this" is
     * answerable but "what else happened in that request" is not.
     */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Filterable
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
