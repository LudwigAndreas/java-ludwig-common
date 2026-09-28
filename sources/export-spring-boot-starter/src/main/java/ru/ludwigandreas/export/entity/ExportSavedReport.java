package ru.ludwigandreas.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * A row in {@code export_saved_report}: an administrator's reusable configuration of a definition.
 *
 * <h2>What is data and what is code</h2>
 *
 * <p>This is the boundary the module draws. The <em>definition</em> is code - typed, compiled,
 * checked at startup. The saved configuration is the part that genuinely varies per organisation and
 * per user: which parameters are prefilled, which of the allowed columns this configuration shows,
 * which format. Nothing here can introduce a query, a column or a format the definition does not
 * already declare, which is what keeps the trade honest.
 *
 * <p>Authored by administrators for reuse by end users, so the column subset stored here is a
 * <em>narrowing</em> of what the definition offers and never a widening: a user running it still
 * gets only the columns their own authorities allow, intersected with this list.
 *
 * <h2>Validated on write and on load</h2>
 *
 * <p>Both, and the second is the one that matters. A definition changes when the service is
 * deployed; a configuration written against it does not. A saved report naming a column that no
 * longer exists must fail loudly and name the column - because the alternative is a file that is
 * quietly narrower than the one it produced last month, and nobody reading it can tell.
 */
@Getter
@Setter
@Entity
@Table(name = "export_saved_report")
@EntityListeners(AuditingEntityListener.class)
public class ExportSavedReport extends GeneratedEntity<UUID> {

    @Column(name = "definition_key", nullable = false, updatable = false, length = 128)
    private String definitionKey;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Parameter values this configuration prefills; a request may still override them. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "jsonb")
    private Map<String, String> parameters;

    /** The columns this configuration shows, always a subset of what the definition declares. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_ids", columnDefinition = "jsonb")
    private List<String> columnIds;

    @Column(name = "filter_expression", columnDefinition = "text")
    private String filterExpression;

    @Column(name = "format_id", length = 32)
    private String formatId;

    /**
     * Bumped on every edit.
     *
     * <p>Distinct from the JPA {@code @Version} above it, which exists to detect a concurrent write.
     * This one is recorded on every run that used the configuration, so a file produced last month
     * can be attributed to the shape the configuration had then rather than to the shape it has now.
     */
    @Column(name = "revision", nullable = false)
    private int revision = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
