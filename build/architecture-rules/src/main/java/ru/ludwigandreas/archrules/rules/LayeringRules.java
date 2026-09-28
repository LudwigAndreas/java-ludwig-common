package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

import static ru.ludwigandreas.archrules.support.ArchitecturePredicates.residingIn;

/**
 * Controller -> Service -> Repository -> Domain, and nothing pointing back.
 *
 * <p>Two rules, on purpose. {@code layering.layered-architecture} is ArchUnit's whole-architecture
 * check, which is the readable one: it names the layer that was violated. {@code
 * layering.controllers-do-not-access-persistence} restates the single most important edge of that
 * graph directly, as a dependency rule between packages, because the layered check can be weakened
 * by accident - defining an extra layer, or a class that ends up in two layers, can make a
 * controller-to-repository dependency disappear from it. The narrow rule cannot be weakened that
 * way, and it is the one whose failure message points straight at the offending line.
 *
 * <p>Layers are declared optional throughout, so a service without messaging or without a storage
 * adapter is not forced to invent one.
 */
public final class LayeringRules implements ArchitectureRuleSet {

    /** Controller -> Service -> Repository, checked as a whole architecture. */
    public static final RuleId LAYERED_ARCHITECTURE = RuleId.of(RuleGroup.LAYERING, "layered-architecture");

    /** No controller may reach the repository or entity packages, whatever the layer check says. */
    public static final RuleId CONTROLLERS_DO_NOT_ACCESS_PERSISTENCE =
            RuleId.of(RuleGroup.LAYERING, "controllers-do-not-access-persistence");

    private static final String CONTROLLER = "Controller";
    private static final String MESSAGING = "Messaging";
    private static final String SERVICE = "Service";
    private static final String REPOSITORY = "Repository";
    private static final String DOMAIN = "Domain";
    private static final String CONFIGURATION = "Configuration";
    private static final String STORAGE = "Storage";
    private static final String MAPPER = "Mapper";

    @Override
    public RuleGroup group() {
        return RuleGroup.LAYERING;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        if (context.hasPackagesFor(PackageRole.CONTROLLER) || context.hasPackagesFor(PackageRole.SERVICE)) {
            rules.add(ArchitectureRule.of(LAYERED_ARCHITECTURE, layeredArchitecture(context),
                    "Remove the dependency that points the wrong way through the layers: move the shared"
                            + " behaviour down into the service layer and call it from both sides, or invert the"
                            + " dependency with an interface owned by the lower layer."));
        }
        if (context.hasPackagesFor(PackageRole.CONTROLLER)) {
            rules.add(ArchitectureRule.of(CONTROLLERS_DO_NOT_ACCESS_PERSISTENCE,
                    controllersDoNotAccessPersistence(context),
                    "Move the query into a service method and have the controller call that instead."
                            + " Going straight to the repository skips the transaction boundary, the"
                            + " authorization the service applies, and the mapping to the API model."));
        }
        return List.copyOf(rules);
    }

    private ArchRule layeredArchitecture(RuleContext context) {
        List<String> considered = context.basePackageIdentifiers();
        LayeredArchitecture architecture = Architectures.layeredArchitecture()
                // Only the service's own code is a layer violation; a dependency on Spring or the
                // JDK is not, and considering it would make every layer depend on every other.
                .consideringOnlyDependenciesInAnyPackage(considered.get(0),
                        considered.subList(1, considered.size()).toArray(String[]::new))
                .optionalLayer(CONTROLLER).definedBy(controllerClasses(context))
                .optionalLayer(MESSAGING).definedBy(residingIn(context.packages(PackageRole.MESSAGING)))
                .optionalLayer(SERVICE).definedBy(residingIn(context.packages(PackageRole.SERVICE)))
                .optionalLayer(REPOSITORY).definedBy(residingIn(context.packages(PackageRole.REPOSITORY)))
                .optionalLayer(DOMAIN).definedBy(residingIn(context.packages(PackageRole.DOMAIN)))
                .optionalLayer(CONFIGURATION).definedBy(residingIn(context.packages(PackageRole.CONFIGURATION)))
                .optionalLayer(STORAGE).definedBy(residingIn(context.packages(PackageRole.STORAGE)))
                // A mapper translates between two layers' models, so it is a layer of its own that
                // may look both ways - not an entry point of whichever package it happens to sit in.
                .optionalLayer(MAPPER).definedBy(residingIn(context.packages(PackageRole.MAPPER)))
                // An entry point is an entry point: nothing in the service may call into it.
                .whereLayer(CONTROLLER).mayNotBeAccessedByAnyLayer()
                // Messaging is half entry point (consumers) and half outbound adapter (producers),
                // so it is reachable from the service layer but not from the web layer.
                .whereLayer(MESSAGING).mayOnlyBeAccessedByLayers(SERVICE, MESSAGING, CONFIGURATION)
                // Configuration is allowed in because that is where the beans are wired together.
                .whereLayer(SERVICE)
                .mayOnlyBeAccessedByLayers(CONTROLLER, MESSAGING, SERVICE, STORAGE, CONFIGURATION, MAPPER)
                .whereLayer(REPOSITORY)
                .mayOnlyBeAccessedByLayers(SERVICE, REPOSITORY, CONFIGURATION, MAPPER);
        return architecture.as("Layering: " + CONTROLLER + " -> " + SERVICE + " -> " + REPOSITORY
                + " -> " + DOMAIN + ", with " + MESSAGING + " entering through " + SERVICE);
    }

    private ArchRule controllersDoNotAccessPersistence(RuleContext context) {
        DescribedPredicate<JavaClass> persistenceClasses = ConventionPredicates.persistencePackageClasses(context);
        return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .that(controllerClasses(context))
                .should(ArchitectureConditions.notDependOnClassesThat(persistenceClasses,
                        "persistence classes - go through the service layer instead"))
                .as("Controllers do not access the persistence layer directly");
    }

    /**
     * The controller classes themselves. Request/response models and mappers often live below the
     * controller package ({@code ..web.dto..}, {@code ..web.mapper..}) and are not entry points -
     * counting them as controllers would forbid a mapper from ever seeing a DTO.
     */
    private static DescribedPredicate<JavaClass> controllerClasses(RuleContext context) {
        return ConventionPredicates.controllerPackageClasses(context);
    }
}
