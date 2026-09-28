package ru.ludwigandreas.example.catalog.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

/**
 * Product category. Extending db-core's {@link AuditedEntity} supplies the generated UUID id,
 * {@code @Version} optimistic locking and the created/updated by-and-at columns, so nothing of that
 * kind is declared here.
 *
 * <p>Its {@link Filterable} fields are reachable from a product query as {@code category/code} and
 * {@code category/name} - association traversal is bounded by
 * {@code odata.filter.max-nested-property-depth}.
 */
@Entity
@Table(name = "product_category")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryEntity extends AuditedEntity<UUID> {

    @Filterable
    @Column(name = "code", nullable = false, updatable = false, length = 64)
    private String code;

    @Filterable
    @Column(name = "name", nullable = false, length = 255)
    private String name;
}
