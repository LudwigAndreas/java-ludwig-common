package ru.ludwigandreas.messaging.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * This module against the shared rule library, including the two rules it contributes.
 *
 * <p>Verifying rather than assuming, which the brief for this module asked for explicitly. A module that
 * ships an architecture rule and does not run it against itself is the module most likely to violate it:
 * it is the one place where the thing being forbidden is also the thing being implemented.
 *
 * <h2>The two contributed rules, and how they behave here</h2>
 *
 * <p>{@code kafka.container-factories-set-an-error-handler} passes because
 * {@code ListenerContainerFactoryBuilder}'s own factory methods call {@code setCommonErrorHandler}
 * directly - which is the branch of the condition that exists for a class with a genuine reason to
 * configure a factory by hand, and this is that class.
 *
 * <p>{@code kafka.no-private-dead-letter-recoverer} passes vacuously: it exempts
 * {@code ru.ludwigandreas.messaging..}, which is this module, because somebody has to construct the one
 * {@code DeadLetterPublishingRecoverer} the platform has. The rule is meaningful in every other module,
 * which is where it runs.
 *
 * <h2>What is switched off, and why</h2>
 *
 * <p>The same reasoning {@code user-settings-spring-boot-starter}'s architecture test sets out: most of the
 * shared library describes a <em>service</em> - controllers on top, a service layer under them, messaging
 * entering through it - and this is a library whose packages are named for what they do.
 *
 * <ul>
 *   <li>{@code layering} - there is no controller, service or repository layer here to order.</li>
 *   <li>{@code kafka.clients-are-confined-to-messaging} and
 *       {@code kafka-contracts.producers-implement-the-publisher-interface} - this module <em>is</em> the
 *       messaging adapter for the whole platform. Its serializers, its dead-letter publisher and its
 *       container builder all touch the Kafka API by definition, and confining them to a package called
 *       {@code messaging} inside a module called {@code messaging} would be a rule satisfied by a
 *       rename.</li>
 *   <li>{@code exceptions.custom-exceptions-extend-the-base-exception} - {@code MessagingException} is
 *       deliberately not a {@code LocalizedException}: nothing here reaches an HTTP response, and
 *       extending that base class would make web-core a non-optional dependency of this module and
 *       therefore of every outbox consumer. The rendering happens at the one boundary that has a locale,
 *       through a contributed {@code ExceptionProblemMapper}.</li>
 *   <li>{@code rest-paths}, {@code web}, {@code persistence}, {@code entity-base},
 *       {@code dto-immutability}, {@code mappers} - this module has no controller, no entity, no
 *       repository, no DTO and no mapper. They pass vacuously today and are switched off so that a future
 *       class with an unlucky name does not fail a rule that was never about this module.</li>
 * </ul>
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.messaging",
        disable = {
                "layering",
                "kafka.clients-are-confined-to-messaging",
                "kafka-contracts.producers-implement-the-publisher-interface",
                "exceptions.custom-exceptions-extend-the-base-exception",
                "rest-paths",
                "web",
                "persistence",
                "entity-base",
                "dto-immutability",
                "mappers"
        })
class ArchitectureTest extends ArchitectureRulesTest {
}
