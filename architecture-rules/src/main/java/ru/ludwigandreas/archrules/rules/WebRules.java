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
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * What may cross the REST boundary, and what the classes on that boundary may do.
 *
 * <p>The through-line is that an HTTP response is a published contract while a JPA entity is an
 * implementation detail of the database. Once an entity is serialised to a client, a column rename
 * is a breaking API change, a lazy association is an accidental N+1 or a serialisation error, and
 * every field the entity gains is published by default. The same reasoning gives each boundary its
 * own model: a class shared between the REST API and a Kafka topic couples two consumers that have
 * no reason to be versioned together.
 */
public final class WebRules implements ArchitectureRuleSet {

    /** No JPA entity appears in a controller method signature. */
    public static final RuleId CONTROLLERS_DO_NOT_EXPOSE_ENTITIES =
            RuleId.of(RuleGroup.WEB, "controllers-do-not-expose-entities");

    /** No repository, {@code EntityManager} or {@code JdbcTemplate} inside a controller. */
    public static final RuleId CONTROLLERS_DO_NOT_USE_PERSISTENCE_TYPES =
            RuleId.of(RuleGroup.WEB, "controllers-do-not-use-persistence-types");

    /** Controllers do not call each other. */
    public static final RuleId CONTROLLERS_DO_NOT_CALL_CONTROLLERS =
            RuleId.of(RuleGroup.WEB, "controllers-do-not-call-controllers");

    /** A REST DTO is not also a JPA entity. */
    public static final RuleId DTOS_ARE_NOT_ENTITIES = RuleId.of(RuleGroup.WEB, "dtos-are-not-jpa-entities");

    /** A REST DTO is not also a Kafka payload. */
    public static final RuleId DTOS_ARE_NOT_EVENT_PAYLOADS =
            RuleId.of(RuleGroup.WEB, "dtos-are-not-event-payloads");

    @Override
    public RuleGroup group() {
        return RuleGroup.WEB;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        if (context.hasPackagesFor(PackageRole.CONTROLLER)
                || !context.annotations(AnnotationRole.CONTROLLER).isEmpty()) {
            rules.add(ArchitectureRule.of(CONTROLLERS_DO_NOT_EXPOSE_ENTITIES, controllersDoNotExposeEntities(context),
                    "Return a DTO instead of the entity and map between them (a MapStruct mapper in the web"
                            + " package). Serialising an entity publishes the database schema as an API contract"
                            + " and drags lazy associations into the response."));
            rules.add(ArchitectureRule.of(CONTROLLERS_DO_NOT_USE_PERSISTENCE_TYPES,
                    controllersDoNotUsePersistenceTypes(context),
                    "Inject a service instead of the repository/EntityManager and move the query behind a"
                            + " service method, so the call runs inside a transaction and the entity never"
                            + " reaches the web layer."));
            rules.add(ArchitectureRule.of(CONTROLLERS_DO_NOT_CALL_CONTROLLERS, controllersDoNotCallControllers(context),
                    "Extract the shared behaviour into a service both controllers call. Calling a controller"
                            + " in-process skips its validation, transaction demarcation and error mapping,"
                            + " because those are applied by the HTTP layer, not by the method."));
        }
        if (context.hasPackagesFor(PackageRole.DTO)) {
            rules.add(ArchitectureRule.of(DTOS_ARE_NOT_ENTITIES, dtosAreNotEntities(context),
                    "Split the class in two: a DTO in the web package with the JSON contract, and an entity in"
                            + " the persistence package with the table mapping, joined by a mapper. One class"
                            + " serving both makes every column rename a breaking API change."));
            if (context.hasPackagesFor(PackageRole.EVENT_PAYLOAD)) {
                rules.add(ArchitectureRule.of(DTOS_ARE_NOT_EVENT_PAYLOADS, dtosAreNotEventPayloads(context),
                    "Give the topic its own payload class. A class shared between the REST API and Kafka"
                            + " couples two sets of consumers that are versioned and released separately."));
            }
        }
        return List.copyOf(rules);
    }

    /**
     * Checks the whole signature, type arguments included, so {@code ResponseEntity<ProductEntity>}
     * and {@code List<ProductEntity>} are caught as well as a bare entity return type.
     */
    private static ArchRule controllersDoNotExposeEntities(RuleContext context) {
        return ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat(ConventionPredicates.controllers(context))
                .should(ArchitectureConditions.notHaveSignatureTypesThat(ConventionPredicates.entities(context),
                        "JPA entities - the REST boundary carries DTOs"))
                .as("Controllers do not expose JPA entities");
    }

    private static ArchRule controllersDoNotUsePersistenceTypes(RuleContext context) {
        DescribedPredicate<JavaClass> persistenceTypes =
                ConventionPredicates.springDataRepositories(context)
                        .or(ConventionPredicates.persistenceAccessTypes(context))
                        .as("repositories, EntityManager or JDBC types");
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.controllers(context))
                .should(ArchitectureConditions.notDependOnClassesThat(persistenceTypes,
                        "persistence types - all data access goes through the service layer"))
                .as("Controllers do not use persistence types directly");
    }

    /**
     * A controller calling another controller is a use case that was never given a home in the
     * service layer: the second controller's transaction demarcation, validation and error mapping
     * all belong to HTTP, and calling it in-process quietly skips them.
     */
    private static ArchRule controllersDoNotCallControllers(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.controllers(context))
                .should(ArchitectureConditions.notDependOnClassesThat(ConventionPredicates.controllers(context),
                        "other controllers - move the shared behaviour into a service"))
                .as("Controllers do not call other controllers");
    }

    private static ArchRule dtosAreNotEntities(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.dtos(context))
                .should(ArchitectureConditions
                        .notBeAnnotatedWithAny(context.annotations(AnnotationRole.PERSISTENT_TYPE),
                                "a persistence annotation - a DTO is an API contract, not a table")
                        .and(ArchitectureConditions.notDependOnClassesThat(
                                ConventionPredicates.persistenceApi(context),
                                "the persistence API - keep mapping code out of the DTO")))
                .as("REST DTOs are not JPA entities");
    }

    private static ArchRule dtosAreNotEventPayloads(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.dtos(context))
                .should().resideOutsideOfPackages(context.packageArray(PackageRole.EVENT_PAYLOAD))
                .as("REST DTOs are not Kafka payloads");
    }
}
