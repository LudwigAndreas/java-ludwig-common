package ru.ludwigandreas.archrules.rules;

import java.util.List;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * The API model is built once and not modified afterwards.
 *
 * <p>A mutable request object is shared with whatever the controller hands it to, and a mutable
 * response object can be changed after validation has passed over it - both turn into defects that
 * only appear under concurrency or after a refactoring moves a line. A record or a builder-built
 * immutable class removes the question.
 *
 * <p>The check is on the compiled API surface - a public {@code setXxx(T)} - and therefore says
 * nothing about how the class was written. That is deliberate and worth stating, because "use Lombok
 * rather than hand-written boilerplate" is explicitly <em>not</em> checkable here: once compiled, a
 * Lombok-generated setter and a hand-written one are the same bytecode. This rule works precisely
 * because it asks about the result instead of the mechanism.
 */
public final class DtoImmutabilityRules implements ArchitectureRuleSet {

    /** No public setters on the API/DTO model. */
    public static final RuleId NO_SETTERS_ON_API_MODELS =
            RuleId.of(RuleGroup.DTO_IMMUTABILITY, "api-models-have-no-setters");

    @Override
    public RuleGroup group() {
        return RuleGroup.DTO_IMMUTABILITY;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        if (!context.hasPackagesFor(PackageRole.DTO)) {
            return List.of();
        }
        return List.of(ArchitectureRule.of(NO_SETTERS_ON_API_MODELS, ArchRuleDefinition.classes()
                        .that(ConventionPredicates.dtos(context))
                        .should(ArchitectureConditions.notDeclarePublicSetters())
                        .as("API models have no public setters"),
                "Make the model immutable: turn it into a record, or keep the fields final and populate"
                        + " them through the constructor or a builder (@Value/@Builder rather than @Data)."
                        + " Callers that need a changed copy get a 'withXxx' method returning a new instance."));
    }
}
