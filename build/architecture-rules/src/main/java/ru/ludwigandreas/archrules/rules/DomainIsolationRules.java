package ru.ludwigandreas.archrules.rules;

import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.ExternalLibrary;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * A domain model that owes nothing to a framework: no Spring, no JPA annotations, no JSON binding.
 *
 * <p>Off by default. It only means anything in a service that actually keeps a domain model separate
 * from its persistence entities - in a service where the entity <em>is</em> the model, switching
 * this on would produce nothing but noise. Services that do run a hexagonal architecture enable the
 * group with {@code enable("domain-isolation")} and point {@link PackageRole#DOMAIN} at their model
 * packages.
 *
 * <p>The payoff is that the domain can be unit-tested without a container, that a Spring or
 * Hibernate upgrade cannot change business behaviour, and that a JSON field rename cannot silently
 * become a domain change.
 */
public final class DomainIsolationRules implements ArchitectureRuleSet {

    /** The domain model does not depend on the Spring framework. */
    public static final RuleId FREE_OF_SPRING = RuleId.of(RuleGroup.DOMAIN_ISOLATION, "domain-is-free-of-spring");

    /** The domain model does not depend on JPA/Hibernate, annotations included. */
    public static final RuleId FREE_OF_PERSISTENCE =
            RuleId.of(RuleGroup.DOMAIN_ISOLATION, "domain-is-free-of-persistence");

    /** The domain model does not depend on a JSON binding library. */
    public static final RuleId FREE_OF_JSON = RuleId.of(RuleGroup.DOMAIN_ISOLATION, "domain-is-free-of-json");

    @Override
    public RuleGroup group() {
        return RuleGroup.DOMAIN_ISOLATION;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        if (!context.hasPackagesFor(PackageRole.DOMAIN)) {
            return List.of();
        }
        return List.of(
                ArchitectureRule.of(FREE_OF_SPRING, freeOf(context, ExternalLibrary.SPRING_FRAMEWORK,
                        "the Spring framework - the domain must be usable without a container"),
                        "Take the Spring type out of the domain class: inject what it needs as a plain"
                                + " constructor parameter and keep the Spring annotation on an adapter in the"
                                + " application layer, so the domain can still be exercised without a container."),
                ArchitectureRule.of(FREE_OF_PERSISTENCE, freeOf(context, ExternalLibrary.PERSISTENCE_API,
                        "JPA/Hibernate - map to a persistence entity instead of annotating the model"),
                        "Introduce a separate JPA entity in the persistence package and map between it and the"
                                + " domain model, instead of annotating the model itself; the storage schema then"
                                + " stops dictating the shape of the business type."),
                ArchitectureRule.of(FREE_OF_JSON, freeOf(context, ExternalLibrary.JSON,
                        "a JSON library - map to a DTO instead of annotating the model"),
                        "Move the JSON annotations onto a DTO and map between it and the domain model, so a"
                                + " change in the wire format cannot become a change in business behaviour."));
    }

    private static ArchRule freeOf(RuleContext context, ExternalLibrary library, String reason) {
        DescribedPredicate<JavaClass> forbidden =
                ArchitecturePredicates.residingIn(context.libraryPackages(library))
                        .as(library.id() + " " + context.libraryPackages(library));
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingIn(context.packages(PackageRole.DOMAIN))
                        .as("domain classes " + context.packages(PackageRole.DOMAIN)))
                .should(ArchitectureConditions.notDependOnClassesThat(forbidden, reason))
                .as("The domain model is free of " + library.id());
    }
}
