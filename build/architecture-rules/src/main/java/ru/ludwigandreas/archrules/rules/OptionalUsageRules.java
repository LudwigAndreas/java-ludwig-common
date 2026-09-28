package ru.ludwigandreas.archrules.rules;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.TypeRole;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;

/**
 * {@code Optional} is a return type, not a field type and not a parameter type.
 *
 * <p>It was designed for exactly one job: expressing "this method may have nothing to give you". As a
 * field it is a serialization and memory liability (not {@code Serializable}, an extra object per
 * instance, and JPA/Jackson both need convincing); as a parameter it pushes the caller into
 * {@code Optional.of(...)} wrapping at every call site and still allows the one thing it was meant to
 * prevent, a {@code null} Optional.
 *
 * <p><strong>Scope, deliberately.</strong> This group checks declared types only. It does not - and
 * will not - try to find methods that return {@code null} where they should return an
 * {@code Optional}: that needs dataflow analysis across method bodies, which is a nullness checker's
 * job (Sonar, NullAway, ErrorProne), not something bytecode structure can answer. Do not try to
 * extend this group in that direction.
 */
public final class OptionalUsageRules implements ArchitectureRuleSet {

    /** No field is declared as {@code Optional}. */
    public static final RuleId NOT_A_FIELD_TYPE = RuleId.of(RuleGroup.OPTIONAL_USAGE, "not-used-as-a-field-type");

    /** No method or constructor takes an {@code Optional} parameter. */
    public static final RuleId NOT_A_PARAMETER_TYPE =
            RuleId.of(RuleGroup.OPTIONAL_USAGE, "not-used-as-a-parameter-type");

    @Override
    public RuleGroup group() {
        return RuleGroup.OPTIONAL_USAGE;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<String> optionalTypes = context.types(TypeRole.OPTIONAL);
        if (optionalTypes.isEmpty()) {
            return List.of();
        }
        return List.of(
                ArchitectureRule.of(NOT_A_FIELD_TYPE, ArchRuleDefinition.fields()
                                .should(ArchitectureConditions.<JavaField>notHaveRawTypeAnyOf(optionalTypes,
                                        "Optional").forSubtype())
                                .as("Optional is not used as a field type"),
                        "Declare the field as the contained type and let it be null internally, or model the"
                                + " absence explicitly (a separate state, a null object). Return Optional from the"
                                + " getter if callers need to be forced to handle absence."),
                ArchitectureRule.of(NOT_A_PARAMETER_TYPE, ArchRuleDefinition.codeUnits()
                                .should(ArchitectureConditions.<JavaCodeUnit>notHaveParameterTypeAnyOf(optionalTypes,
                                        "an Optional").forSubtype())
                                .as("Optional is not used as a parameter type"),
                        "Take the value itself as the parameter and provide an overload (or a builder) for the"
                                + " case where the caller has nothing to pass; an Optional parameter forces every"
                                + " call site to wrap and can still be passed as null."));
    }
}
