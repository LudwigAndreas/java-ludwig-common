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
 * Kafka as one more adapter rather than a second, parallel architecture.
 *
 * <p>A consumer is an entry point exactly like a controller, and gets the same treatment: it
 * translates a message into a call on the service layer and does not query the database itself. A
 * payload is a published contract exactly like a DTO, and gets the same treatment: its own class,
 * free of JPA annotations, not shared with the REST API - a topic and an HTTP endpoint have
 * different consumers, different compatibility requirements and different release cadences.
 *
 * <p>The whole group is inert in a service with no Kafka: nothing matches, and the rules pass.
 */
public final class KafkaRules implements ArchitectureRuleSet {

    /** Only the messaging packages talk to the Kafka API. */
    public static final RuleId CLIENTS_ARE_CONFINED = RuleId.of(RuleGroup.KAFKA, "clients-are-confined-to-messaging");

    /** {@code @KafkaListener} classes reside in the messaging packages. */
    public static final RuleId CONSUMERS_IN_MESSAGING_PACKAGES =
            RuleId.of(RuleGroup.KAFKA, "consumers-reside-in-messaging-packages");

    /** A consumer goes through the service layer, like any other entry point. */
    public static final RuleId CONSUMERS_DO_NOT_USE_REPOSITORIES =
            RuleId.of(RuleGroup.KAFKA, "consumers-do-not-use-repositories");

    /** Payload classes carry no JPA annotations. */
    public static final RuleId PAYLOADS_ARE_FREE_OF_JPA = RuleId.of(RuleGroup.KAFKA, "payloads-are-free-of-jpa");

    /** Payload classes are not the REST DTOs. */
    public static final RuleId PAYLOADS_ARE_NOT_REST_DTOS = RuleId.of(RuleGroup.KAFKA, "payloads-are-not-rest-dtos");

    /** Messaging does not depend on the web layer. */
    public static final RuleId MESSAGING_DOES_NOT_DEPEND_ON_CONTROLLERS =
            RuleId.of(RuleGroup.KAFKA, "messaging-does-not-depend-on-controllers");

    @Override
    public RuleGroup group() {
        return RuleGroup.KAFKA;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        // The confinement rule stands even when no messaging package is configured: a service that
        // declares no home for the Kafka client is a service that may not use one anywhere.
        rules.add(ArchitectureRule.of(CLIENTS_ARE_CONFINED, clientsAreConfined(context),
                "Move the Kafka call into a producer or consumer class in the messaging package and depend on"
                        + " that from here, so retries, headers and serialization stay in one place."));
        if (context.hasPackagesFor(PackageRole.MESSAGING)) {
            rules.add(ArchitectureRule.of(CONSUMERS_IN_MESSAGING_PACKAGES, consumersInMessagingPackages(context),
                    "Move the @KafkaListener class into the messaging package, next to the other adapters."));
            rules.add(ArchitectureRule.of(MESSAGING_DOES_NOT_DEPEND_ON_CONTROLLERS,
                    messagingDoesNotDependOnControllers(context),
                    "Extract what the messaging class needs into a service and call that; one entry point"
                            + " calling another skips the HTTP layer that gives the controller its behaviour."));
        }
        rules.add(ArchitectureRule.of(CONSUMERS_DO_NOT_USE_REPOSITORIES, consumersDoNotUseRepositories(context),
                "Call a service method from the listener instead of the repository, so the message is handled"
                        + " inside the same transaction and validation as any other entry point."));
        if (context.hasPackagesFor(PackageRole.EVENT_PAYLOAD)) {
            rules.add(ArchitectureRule.of(PAYLOADS_ARE_FREE_OF_JPA, payloadsAreFreeOfJpa(context),
                    "Give the topic its own payload class without JPA annotations and map to it when"
                            + " publishing; a payload that is also a table makes every schema change a wire"
                            + " format change."));
            if (context.hasPackagesFor(PackageRole.DTO)) {
                rules.add(ArchitectureRule.of(PAYLOADS_ARE_NOT_REST_DTOS, payloadsAreNotRestDtos(context),
                        "Give the topic its own payload class instead of reusing the REST DTO - the two have"
                                + " different consumers and different compatibility guarantees."));
            }
        }
        return List.copyOf(rules);
    }

    private static ArchRule clientsAreConfined(RuleContext context) {
        List<String> allowed = new ArrayList<>(context.packages(PackageRole.MESSAGING));
        allowed.addAll(context.packages(PackageRole.CONFIGURATION));
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(allowed)
                        .as("classes outside the messaging and configuration packages " + allowed))
                .should(ArchitectureConditions.notDependOnClassesThat(ConventionPredicates.kafkaApi(context),
                        "the Kafka API - publish and consume through the messaging adapters"))
                .as("Kafka clients are confined to the messaging packages");
    }

    private static ArchRule consumersInMessagingPackages(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.kafkaConsumers(context))
                .should().resideInAnyPackage(context.packageArray(PackageRole.MESSAGING))
                .as("Kafka consumers reside in the messaging packages");
    }

    private static ArchRule consumersDoNotUseRepositories(RuleContext context) {
        DescribedPredicate<JavaClass> forbidden = ConventionPredicates.springDataRepositories(context)
                .or(ConventionPredicates.persistenceAccessTypes(context))
                .as("repositories or persistence access types");
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.kafkaConsumers(context))
                .should(ArchitectureConditions.notDependOnClassesThat(forbidden,
                        "persistence - a consumer calls the service layer, like a controller does"))
                .as("Kafka consumers do not use repositories directly");
    }

    private static ArchRule payloadsAreFreeOfJpa(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.eventPayloads(context))
                .should(ArchitectureConditions
                        .notBeAnnotatedWithAny(context.annotations(AnnotationRole.PERSISTENT_TYPE),
                                "a persistence annotation - a payload is a wire contract, not a table")
                        .and(ArchitectureConditions.notDependOnClassesThat(
                                ConventionPredicates.persistenceApi(context),
                                "the persistence API")))
                .as("Kafka payloads are free of JPA");
    }

    /**
     * Checked from both ends: this rule keeps the payload packages clear of the REST models, and
     * {@code web.dtos-are-not-event-payloads} keeps the DTO packages clear of the payloads.
     */
    private static ArchRule payloadsAreNotRestDtos(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.eventPayloads(context))
                .should(ArchitectureConditions.notDependOnClassesThat(ConventionPredicates.dtos(context),
                        "REST DTOs - each boundary owns its own model"))
                .as("Kafka payloads are not REST DTOs");
    }

    private static ArchRule messagingDoesNotDependOnControllers(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.messagingClasses(context))
                .should(ArchitectureConditions.notDependOnClassesThat(
                        ConventionPredicates.controllerPackageClasses(context),
                        "controllers - two entry points must not call each other"))
                .as("Messaging does not depend on controllers");
    }
}
