package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;

/**
 * The identity projection's single write path.
 *
 * <p>Every feed - a Kafka subscription, a pulled directory, a webhook, a seed - writes users through
 * {@code IdentityProjection}, which applies ordering, staleness, source ownership, role mapping and authority-cache
 * eviction. Saving or deleting through the projection's repositories from anywhere else writes a user with none of
 * them: a role that a replay could restore, a subject another provider owns, an authority cache still serving the
 * old roles. Reading the repositories stays allowed; the projection is meant to be queried.
 *
 * <p>Matched by name, so a module enabling the rules does not depend on the projection.
 */
public final class IdentityRules implements ArchitectureRuleSet {

    /** No class outside the projection saves or deletes through its repositories. */
    public static final RuleId WRITES_GO_THROUGH_THE_PROJECTION =
            RuleId.of(RuleGroup.IDENTITY, "writes-go-through-the-projection");

    private static final String PROJECTION_PACKAGE = "ru.ludwigandreas.identity";
    private static final String REPOSITORY_PACKAGE = PROJECTION_PACKAGE + ".repository";

    @Override
    public RuleGroup group() {
        return RuleGroup.IDENTITY;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(ArchitectureRule.of(WRITES_GO_THROUGH_THE_PROJECTION, writesGoThroughTheProjection(),
                "Apply an IdentityChange through IdentityProjection (apply or applyAll) instead of saving the entity."
                        + " The projection orders changes by occurredAt, refuses a subject another source owns, maps"
                        + " the source's roles and evicts the authority cache after commit; a direct save does none"
                        + " of that."));
    }

    private static ArchRule writesGoThroughTheProjection() {
        return ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage(PROJECTION_PACKAGE + "..")
                .should().callMethodWhere(new DescribedPredicate<JavaCall<?>>(
                        "a save or delete method of an identity projection repository") {
                    @Override
                    public boolean test(JavaCall<?> call) {
                        String owner = call.getTargetOwner().getPackageName();
                        String name = call.getName();
                        return owner.equals(REPOSITORY_PACKAGE)
                                && (name.startsWith("save") || name.startsWith("delete"));
                    }
                })
                .allowEmptyShould(true);
    }
}
