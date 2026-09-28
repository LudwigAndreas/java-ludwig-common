package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
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

    /** Every listener container factory a service builds has an error handler. */
    public static final RuleId CONTAINER_FACTORIES_SET_AN_ERROR_HANDLER =
            RuleId.of(RuleGroup.KAFKA, "container-factories-set-an-error-handler");

    /** Dead-lettering is the platform's, not each module's. */
    public static final RuleId NO_PRIVATE_DEAD_LETTER_RECOVERER =
            RuleId.of(RuleGroup.KAFKA, "no-private-dead-letter-recoverer");

    /** The type that publishes a record to a dead-letter topic. */
    private static final String DEAD_LETTER_RECOVERER =
            "org.springframework.kafka.listener.DeadLetterPublishingRecoverer";

    /** The factory type a service registers per consumer. */
    private static final String CONTAINER_FACTORY =
            "org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory";

    /** The setter that attaches an error handler to one. */
    private static final String SET_ERROR_HANDLER = "setCommonErrorHandler";

    /** The platform builder that sets one on every factory it returns. */
    private static final String PLATFORM_BUILDER =
            "ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder";

    /** The package that legitimately owns dead-lettering and the shared factory. */
    private static final String MESSAGING_MODULE_PACKAGE = "ru.ludwigandreas.messaging..";

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
        rules.add(ArchitectureRule.of(CONTAINER_FACTORIES_SET_AN_ERROR_HANDLER,
                containerFactoriesSetAnErrorHandler(context),
                "Build the factory with messaging-spring-boot-starter's ListenerContainerFactoryBuilder,"
                        + " which sets one - or call setCommonErrorHandler on it here. A factory with no"
                        + " error handler inherits Spring Boot's default, which retries ten times with no"
                        + " backoff and then logs the failure and moves on: the record is dropped and the"
                        + " only trace is a log line."));
        rules.add(ArchitectureRule.of(NO_PRIVATE_DEAD_LETTER_RECOVERER, noPrivateDeadLetterRecoverer(),
                "Get the dead-letter topic from messaging-spring-boot-starter's container factory builder"
                        + " instead of constructing a DeadLetterPublishingRecoverer here. A second one means"
                        + " a second destination naming convention, a second partition decision and a send"
                        + " that is counted and audited in one place and not the other."));
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

    /**
     * Every method that returns a listener container factory attaches an error handler to it.
     *
     * <p>This is the rule that makes the two worst defects of the platform's pre-consolidation Kafka wiring
     * impossible to reintroduce, and both were defects of <em>omission</em> rather than of commission -
     * which is exactly the kind a review does not catch, because there is nothing on the screen to object
     * to:
     *
     * <ul>
     *   <li>one module declared a factory with {@code AckMode.MANUAL} and no error handler, so a listener
     *       that threw before acknowledging had its record redelivered forever, with no backoff, at full
     *       speed;</li>
     *   <li>another declared no factory at all and so inherited Boot's default handler, which retries ten
     *       times with no backoff and then logs and moves on - dropping the record.</li>
     * </ul>
     *
     * <p>Satisfied either by calling {@code setCommonErrorHandler} in the method, or by obtaining the
     * factory from {@code ListenerContainerFactoryBuilder}, which sets one on everything it returns. The
     * second is the intended shape and the first is what a service that has a genuine reason to differ
     * does.
     *
     * <h2>What this cannot see, and therefore does not claim</h2>
     *
     * <p><b>A factory built through a helper method.</b> The condition looks at the method's own calls, not
     * transitively through every method it calls. Following the call graph would mean either an arbitrary
     * depth limit or a whole-program analysis that ArchUnit's per-class model does not support, and the
     * version with a depth limit would pass or fail depending on how somebody happened to factor their
     * configuration class - which is worse than a rule with a stated boundary. The intended shape is one
     * expression returning the builder's result, and that is what this checks.
     *
     * <p><b>A factory Boot auto-configures.</b> There is no method of the service's to look at, so the
     * second defect above is caught here only in its visible form - a listener naming no
     * {@code containerFactory} is a thing the platform's own modules now do not do, and a rule over
     * annotation attributes would flag every listener in a service that deliberately uses Boot's factory
     * with its own configured handler.
     */
    private static ArchRule containerFactoriesSetAnErrorHandler(RuleContext context) {
        DescribedPredicate<JavaMethod> factoryMethods = DescribedPredicate.describe(
                "methods of this service returning a " + CONTAINER_FACTORY,
                method -> ConventionPredicates.ownCode(context).test(method.getOwner())
                        && CONTAINER_FACTORY.equals(method.getRawReturnType().getName()));
        return ArchRuleDefinition.methods()
                .that(factoryMethods)
                .should(attachAnErrorHandler())
                .as("Kafka listener container factories set a CommonErrorHandler");
    }

    private static ArchCondition<JavaMethod> attachAnErrorHandler() {
        return new ArchCondition<>("set a CommonErrorHandler, or come from the platform's factory builder") {
            @Override
            public void check(JavaMethod item, ConditionEvents events) {
                boolean satisfied = false;
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    if (attachesOne(call)) {
                        satisfied = true;
                        break;
                    }
                }
                events.add(new SimpleConditionEvent(item, satisfied,
                        item.getFullName() + (satisfied ? " sets" : " does not set")
                                + " an error handler on the container factory it returns"));
            }

            private boolean attachesOne(JavaMethodCall call) {
                String owner = call.getTargetOwner().getName();
                if (PLATFORM_BUILDER.equals(owner)) {
                    return true;
                }
                return CONTAINER_FACTORY.equals(owner) && SET_ERROR_HANDLER.equals(call.getName());
            }
        };
    }

    /**
     * Nothing outside the messaging module constructs its own dead-letter publisher.
     *
     * <p>The structural half of a two-part fence. The other half is the dead-letter <em>suffix</em>, which
     * is a string literal and therefore invisible to ArchUnit - a {@code static final String}'s value is a
     * constant-pool entry {@code JavaField} does not expose - so it is Checkstyle's
     * {@code SecondDeadLetterSuffix} rule instead. This is the same split the audit consolidation already
     * drew and for the same reason: {@code architecture-rules} owns structure and dependencies,
     * {@code checkstyle-rules} owns source text.
     *
     * <p>Not scoped by {@code hasPackagesFor}: a service that declares no messaging package is a service
     * that may not dead-letter anywhere, and a service with no Kafka has nothing to match and passes.
     */
    private static ArchRule noPrivateDeadLetterRecoverer() {
        DescribedPredicate<JavaClass> recoverer = DescribedPredicate.describe(
                "Spring Kafka's DeadLetterPublishingRecoverer - dead-lettering is the platform's",
                javaClass -> DEAD_LETTER_RECOVERER.equals(javaClass.getName()));
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(MESSAGING_MODULE_PACKAGE)))
                .should(ArchitectureConditions.notDependOnClassesThat(recoverer,
                        "a dead-letter publisher of their own")
                        .as("not depend on a dead-letter publisher of their own"))
                .as("Only the messaging module declares a dead-letter publisher");
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
