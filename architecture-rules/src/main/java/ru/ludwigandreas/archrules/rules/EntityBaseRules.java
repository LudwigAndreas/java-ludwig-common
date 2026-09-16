package ru.ludwigandreas.archrules.rules;

import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.TypeRole;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * Every entity carries the same identity and audit columns, because they all inherit them.
 *
 * <p>An entity that defines its own id or its own {@code createdAt} is not merely inconsistent: it
 * is invisible to whatever reads those columns generically - the auditing listener, the soft-delete
 * filter, the optimistic-locking convention, the reporting queries that assume every table has
 * {@code updated_at}. Inheriting one base class is the only way that assumption stays true as tables
 * are added.
 *
 * <p>Checked against {@code @Entity} only. A {@code @MappedSuperclass} is usually the base class
 * itself and an {@code @Embeddable} has no identity of its own, so neither is asked to extend
 * anything.
 */
public final class EntityBaseRules implements ArchitectureRuleSet {

    /** Every {@code @Entity} extends the configured base entity. */
    public static final RuleId ENTITIES_EXTEND_BASE =
            RuleId.of(RuleGroup.ENTITY_BASE, "entities-extend-the-base-entity");

    private static final String BASE_ENTITY_PROPERTY = "architecture.rules.conventions.types.base-entity";

    @Override
    public RuleGroup group() {
        return RuleGroup.ENTITY_BASE;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        DescribedPredicate<JavaClass> entities =
                ConventionPredicates.annotatedAs(context, AnnotationRole.ENTITY).as("JPA entities");
        ArchRule rule = RequiredConfiguration.mustExtendConfiguredType(context, TypeRole.BASE_ENTITY, entities,
                "JPA entities", BASE_ENTITY_PROPERTY, "ru.ludwigandreas.db.core.entity.AuditableEntity",
                ENTITIES_EXTEND_BASE.value());
        return List.of(ArchitectureRule.of(ENTITIES_EXTEND_BASE, rule,
                "Extend the shared base entity so the entity inherits the standard id and audit columns"
                        + " (createdAt/updatedAt and the auditing listener) instead of redeclaring them;"
                        + " remove the entity's own id/audit fields as part of the change."));
    }
}
