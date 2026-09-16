package ru.ludwigandreas.archrules.rules;

import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.ConventionSetting;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * A class that presents itself as a mapper is a MapStruct interface.
 *
 * <p>The value is uniformity of the seam between two model layers: a generated mapper cannot silently
 * drop a field (an unmapped target is a compile-time warning or error), it needs no test of its own,
 * and it cannot grow business logic without someone noticing that a method body appeared where an
 * interface used to be.
 *
 * <p><strong>Scope, deliberately.</strong> This rule locks the convention for classes that are
 * already identified as mappers - by package, or by a configurable name suffix. It does not detect
 * mapping logic written inline somewhere else, and no attempt should be made to add that: there is no
 * structural difference in bytecode between a legitimate conversion and one that "should have been a
 * MapStruct mapper", which makes it a code-review judgment rather than a rule.
 *
 * <p>MapStruct's generated {@code *MapperImpl} classes are excluded, since they implement a mapper
 * interface that the rule has already checked. That exclusion is by assignability rather than by name
 * because {@code @Generated} carries source retention and is simply not in the bytecode. The nested
 * classes the generator emits alongside them are excluded too - only a top-level type can be the
 * mapper that the convention is about.
 */
public final class MapperConventionRules implements ArchitectureRuleSet {

    /** Mappers are interfaces annotated with {@code @Mapper}. */
    public static final RuleId MAPPERS_ARE_MAPSTRUCT_INTERFACES =
            RuleId.of(RuleGroup.MAPPERS, "mappers-are-mapstruct-interfaces");

    @Override
    public RuleGroup group() {
        return RuleGroup.MAPPERS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<String> mapperAnnotations = context.annotations(AnnotationRole.MAPPER);
        if (mapperAnnotations.isEmpty()) {
            return List.of();
        }
        ArchRule rule = ArchRuleDefinition.classes()
                .that(mapperCandidates(context))
                .should().beInterfaces()
                .andShould(ArchitectureConditions.beAnnotatedWithAny(mapperAnnotations, "@Mapper"))
                .as("Mappers are MapStruct interfaces");
        return List.of(ArchitectureRule.of(MAPPERS_ARE_MAPSTRUCT_INTERFACES, rule,
                "Turn the mapper into an interface annotated with @org.mapstruct.Mapper and let the"
                        + " annotation processor generate the implementation; move any logic that is not a"
                        + " field-by-field conversion out of it, into the service that owns the rule."
                        + " If the class is not a mapper at all, move it out of the mapper package or rename it."));
    }

    private static DescribedPredicate<JavaClass> mapperCandidates(RuleContext context) {
        DescribedPredicate<JavaClass> byPackage = ConventionPredicates.inRole(context, PackageRole.MAPPER);
        DescribedPredicate<JavaClass> candidates = byPackage;
        for (String suffix : context.settings(ConventionSetting.MAPPER_NAME_SUFFIX)) {
            candidates = candidates.or(JavaClass.Predicates.simpleNameEndingWith(suffix)
                    .and(ConventionPredicates.ownCode(context)));
        }
        DescribedPredicate<JavaClass> generatedImplementations = JavaClass.Predicates.assignableTo(
                ConventionPredicates.annotatedAs(context, AnnotationRole.MAPPER)
                        .and(JavaClass.Predicates.INTERFACES));
        return candidates
                // Only a top-level type can be the mapper. MapStruct's generated implementation
                // carries anonymous inner classes (Impl$1 and friends) that live in the mapper
                // package and are, unavoidably, neither interfaces nor annotated.
                .and(JavaClass.Predicates.TOP_LEVEL_CLASSES)
                .and(DescribedPredicate.not(generatedImplementations))
                .and(DescribedPredicate.not(ArchitecturePredicates.residingIn(
                        context.packages(PackageRole.CONFIGURATION))))
                .as("mappers (packages " + context.packages(PackageRole.MAPPER) + " or name suffix "
                        + context.settings(ConventionSetting.MAPPER_NAME_SUFFIX) + ")");
    }
}
