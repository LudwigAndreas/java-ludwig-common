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

    /**
     * A service does not build a QueryDSL path from a caller-supplied property name.
     *
     * <p>{@code PathBuilder} resolves a property path at runtime and is therefore the one construct in
     * this platform's data access that the compiler does not check: a renamed column becomes a
     * {@code QueryException} at request time instead of a compile error, which is the whole reason the
     * QueryDSL-only convention exists. A fixed path belongs on a generated Q-type; the only paths that
     * legitimately need building are the ones that arrive as strings from a caller, and those belong to
     * whichever module validated them.
     *
     * <p>In this platform that module is {@code odata-filter-spring-boot-starter}: it owns the
     * {@code @Filterable} vocabulary, so it is the only place that knows a path was checked, and its
     * {@code ODataPaths} exposes the result. A repository building its own has re-implemented validated
     * resolution without the validation - and, worse, has taken on an invisible obligation to address
     * the same query alias the predicate uses. When the two disagree JPQL does not fail; it cross-joins
     * the table to itself and returns rows that are quietly wrong.
     *
     * <p>Two repositories in this repository each held a private copy of that walk before
     * {@code ODataPaths} existed. This rule is why there cannot be a third.
     *
     * <p><b>Why ArchUnit and not Checkstyle:</b> this is a type reference, which is exactly what
     * bytecode carries. The companion condition - that the alias strings match - is a value and so is
     * checked at runtime by {@code ODataPaths.requireMatchingAlias} instead; neither tool can read it.
     */
    public static final RuleId DYNAMIC_PATHS_ARE_NOT_HAND_BUILT =
            RuleId.of(RuleGroup.PERSISTENCE, "dynamic-paths-are-not-hand-built");

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
        rules.add(ArchitectureRule.of(DYNAMIC_PATHS_ARE_NOT_HAND_BUILT, dynamicPathsAreNotHandBuilt(context),
                    "Take the ordering expressions from the module that validated the paths"
                            + " (ODataPaths.orderSpecifiers) instead of building a PathBuilder here. A"
                            + " hand-built path is resolved at runtime, is not checked against the entity's"
                            + " filterable declarations, and silently cross-joins if its alias differs from"
                            + " the predicate's."));
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

    /**
     * Nothing in a service builds a {@code PathBuilder}. There is no allowed package: the legitimate
     * users are library modules that own a caller-facing path vocabulary, and a service's analysis never
     * imports their classes, so an exemption list here would only ever be wrong in one direction.
     */
    private static ArchRule dynamicPathsAreNotHandBuilt(RuleContext context) {
        return ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.querydsl.core.types.dsl.PathBuilder")
                .as("A caller-supplied property path is resolved by the module that validated it")
                .because("a PathBuilder in a service resolves a property name at runtime that nothing"
                        + " type-checks, and must address the same query alias as the predicate or the"
                        + " query cross-joins instead of failing");
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
