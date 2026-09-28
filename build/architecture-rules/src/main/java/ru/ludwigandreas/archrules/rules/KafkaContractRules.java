package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.TypeRole;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * Producers and consumers implement the service's own messaging contracts rather than reaching for
 * {@code KafkaTemplate} wherever a message needs publishing.
 *
 * <p>This is the contract half of the Kafka isolation already enforced by {@link KafkaRules}: that
 * group says where the Kafka API may appear, this one says what the class that uses it has to be. The
 * difference matters in practice - a package boundary still allows five different publishers with
 * five different retry, header and serialization behaviours, all inside {@code ..messaging..}. One
 * interface gives the service a single place to add tracing headers, an outbox, a retry policy or a
 * test double.
 *
 * <p>Both rules are phrased so that a service without Kafka has nothing to match, and the producer
 * rule only demands a configured interface name from a service that actually publishes.
 */
public final class KafkaContractRules implements ArchitectureRuleSet {

    /** Classes using the Kafka producer API implement the configured publisher interface. */
    public static final RuleId PRODUCERS_IMPLEMENT_PUBLISHER =
            RuleId.of(RuleGroup.KAFKA_CONTRACTS, "producers-implement-the-publisher-interface");

    /** Kafka consumers implement the configured handler interface. Built only once one is configured. */
    public static final RuleId CONSUMERS_IMPLEMENT_HANDLER =
            RuleId.of(RuleGroup.KAFKA_CONTRACTS, "consumers-implement-the-handler-interface");

    private static final String PUBLISHER_PROPERTY = "architecture.rules.conventions.types.event-publisher";

    @Override
    public RuleGroup group() {
        return RuleGroup.KAFKA_CONTRACTS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        rules.add(ArchitectureRule.of(PRODUCERS_IMPLEMENT_PUBLISHER, producersImplementPublisher(context),
                "Publish through the shared publisher interface instead of holding a KafkaTemplate:"
                        + " let this class implement it, or inject the existing implementation and delete"
                        + " the direct template usage. That is what keeps headers, retries and serialization"
                        + " identical for every topic the service writes to."));
        // The consumer contract is optional: plenty of services keep their listeners as plain
        // annotated methods, and requiring an interface they have not defined would be noise.
        if (context.hasTypesFor(TypeRole.EVENT_CONSUMER)) {
            rules.add(ArchitectureRule.of(CONSUMERS_IMPLEMENT_HANDLER, consumersImplementHandler(context),
                    "Let the listener class implement the shared consumer contract "
                            + context.types(TypeRole.EVENT_CONSUMER)
                            + " so that acknowledgement, idempotency and error handling are the same"
                            + " for every topic the service reads."));
        }
        return List.copyOf(rules);
    }

    /**
     * A producer is recognised by what it uses, not by where it lives: any class of the service that
     * depends on the Kafka producer API. Configuration classes are excluded - somebody has to build
     * the {@code KafkaTemplate} bean.
     */
    private static ArchRule producersImplementPublisher(RuleContext context) {
        DescribedPredicate<JavaClass> producers = ConventionPredicates.ownCode(context)
                .and(ArchitecturePredicates.dependingOnClassesThat(ConventionPredicates.kafkaApi(context),
                        "the Kafka producer API"))
                .and(DescribedPredicate.not(ConventionPredicates.configurationClasses(context)))
                .and(DescribedPredicate.not(ConventionPredicates.kafkaConsumers(context)))
                .as("classes using the Kafka API outside configuration and consumers");
        return RequiredConfiguration.mustExtendConfiguredType(context, TypeRole.EVENT_PUBLISHER, producers,
                "Kafka producers", PUBLISHER_PROPERTY, "com.acme.messaging.EventPublisher",
                PRODUCERS_IMPLEMENT_PUBLISHER.value());
    }

    private static ArchRule consumersImplementHandler(RuleContext context) {
        return RequiredConfiguration.mustExtendConfiguredType(context, TypeRole.EVENT_CONSUMER,
                ConventionPredicates.kafkaConsumers(context), "Kafka consumers",
                "architecture.rules.conventions.types.event-consumer", "com.acme.messaging.EventHandler",
                CONSUMERS_IMPLEMENT_HANDLER.value());
    }
}
