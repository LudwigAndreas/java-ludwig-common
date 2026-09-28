package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * Where persistence is allowed to live: entities in the entity package, repositories in the
 * repository package, and the {@code EntityManager} nowhere else.
 *
 * <p>The point is not tidiness. A JPA entity is a mutable object with a database identity and a
 * lifecycle attached to a transaction; the further it travels, the more places can mutate it outside
 * a transaction, and the more of the schema becomes load-bearing elsewhere. Keeping the persistence
 * types in one package is what makes it possible to see, by looking at imports, everything that can
 * issue a query.
 */
public final class PersistenceRules implements ArchitectureRuleSet {

    /** {@code @Entity} and friends only appear in the entity packages. */
    public static final RuleId ENTITIES_IN_ENTITY_PACKAGES =
            RuleId.of(RuleGroup.PERSISTENCE, "entities-reside-in-entity-packages");

    /** Spring Data repositories only appear in the repository packages. */
    public static final RuleId REPOSITORIES_IN_REPOSITORY_PACKAGES =
            RuleId.of(RuleGroup.PERSISTENCE, "repositories-reside-in-repository-packages");

    /** A Spring Data repository is an interface, never a class. */
    public static final RuleId REPOSITORIES_ARE_INTERFACES =
            RuleId.of(RuleGroup.PERSISTENCE, "repositories-are-interfaces");

    /** Only the service layer (and Spring's own wiring) may reach a repository. */
    public static final RuleId REPOSITORIES_USED_ONLY_BY_SERVICES =
            RuleId.of(RuleGroup.PERSISTENCE, "repositories-are-used-only-by-services");

    /** {@code EntityManager}, Hibernate {@code Session} and JDBC stay in the persistence layer. */
    public static final RuleId PERSISTENCE_CONTEXT_IS_CONFINED =
            RuleId.of(RuleGroup.PERSISTENCE, "persistence-context-is-confined");

    @Override
    public RuleGroup group() {
        return RuleGroup.PERSISTENCE;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        if (context.hasPackagesFor(PackageRole.ENTITY)) {
            rules.add(ArchitectureRule.of(ENTITIES_IN_ENTITY_PACKAGES, entitiesInEntityPackages(context),
                    "Move the @Entity class into the entity package. Keeping every persistent type in one"
                            + " place is what makes it possible to see, from the imports alone, what can touch"
                            + " the database."));
        }
        if (context.hasPackagesFor(PackageRole.REPOSITORY)) {
            rules.add(ArchitectureRule.of(REPOSITORIES_IN_REPOSITORY_PACKAGES,
                    repositoriesInRepositoryPackages(context),
                    "Move the repository into the repository package, next to the other data access types."));
        }
        // Confinement, unlike placement, does not need a configured home package to mean something.
        rules.add(ArchitectureRule.of(REPOSITORIES_USED_ONLY_BY_SERVICES, repositoriesUsedOnlyByServices(context),
                    "Call a service method instead of the repository. A query issued outside the service"
                            + " layer runs outside its transaction and outside whatever authorization the"
                            + " service applies."));
        rules.add(ArchitectureRule.of(PERSISTENCE_CONTEXT_IS_CONFINED, persistenceContextIsConfined(context),
                    "Move the EntityManager/JdbcTemplate usage into a repository (or a repository fragment)"
                            + " and call that. An entity fetched outside the persistence layer is detached"
                            + " somewhere unpredictable, and the SQL stops being findable."));
        rules.add(ArchitectureRule.of(REPOSITORIES_ARE_INTERFACES, repositoriesAreInterfaces(context),
                    "Declare the repository as an interface and let Spring Data implement it; put hand-written"
                            + " query code in a custom fragment interface with its own Impl class rather than"
                            + " implementing the repository marker directly."));
        return List.copyOf(rules);
    }

    private static ArchRule entitiesInEntityPackages(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.annotatedAs(context, AnnotationRole.PERSISTENT_TYPE)
                        .as("persistent types " + context.annotations(AnnotationRole.PERSISTENT_TYPE)))
                .should().resideInAnyPackage(context.packageArray(PackageRole.ENTITY))
                .as("JPA entities reside in the entity packages");
    }

    private static ArchRule repositoriesInRepositoryPackages(RuleContext context) {
        DescribedPredicate<JavaClass> repositories = ConventionPredicates.springDataRepositories(context)
                .or(ConventionPredicates.annotatedAs(context, AnnotationRole.REPOSITORY))
                .as("repositories");
        return ArchRuleDefinition.classes()
                .that(repositories)
                .should().resideInAnyPackage(context.packageArray(PackageRole.REPOSITORY))
                .as("Repositories reside in the repository packages");
    }

    /**
     * A Spring Data repository that is a class has stopped being a declarative query interface: it
     * is a hand-written data access object that happens to inherit the marker, and none of the
     * guarantees the rest of these rules make about repositories hold for it any more.
     */
    private static ArchRule repositoriesAreInterfaces(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.springDataRepositories(context))
                .should().beInterfaces()
                .as("Spring Data repositories are interfaces");
    }

    private static ArchRule repositoriesUsedOnlyByServices(RuleContext context) {
        List<String> allowed = new ArrayList<>(context.packages(PackageRole.SERVICE));
        allowed.addAll(context.packages(PackageRole.REPOSITORY));
        allowed.addAll(context.packages(PackageRole.CONFIGURATION));
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(allowed)
                        .as("classes outside the service, repository and configuration packages " + allowed))
                .should(ArchitectureConditions.notDependOnClassesThat(
                        ConventionPredicates.springDataRepositories(context),
                        "repositories - only the service layer queries the database"))
                .as("Repositories are used only by the service layer");
    }

    private static ArchRule persistenceContextIsConfined(RuleContext context) {
        List<String> allowed = new ArrayList<>(context.packages(PackageRole.REPOSITORY));
        allowed.addAll(context.packages(PackageRole.ENTITY));
        allowed.addAll(context.packages(PackageRole.CONFIGURATION));
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(allowed)
                        .as("classes outside the persistence and configuration packages " + allowed))
                .should(ArchitectureConditions.notDependOnClassesThat(
                        ConventionPredicates.persistenceAccessTypes(context),
                        "EntityManager/Session/JDBC types - query from the repository layer"))
                .as("The persistence context is confined to the persistence layer");
    }
}
