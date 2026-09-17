package ru.ludwigandreas.notification.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;
import ru.ludwigandreas.notification.NotificationApplication;

/**
 * The whole architecture test of this service.
 *
 * <p>Every rule of the shared library becomes its own test here, named by its id, so a violation
 * shows up in the build report as, say, {@code persistence.entities-reside-in-entity-packages}
 * rather than as one long assertion failure.
 *
 * <p>Object storage is switched off because this service has none: the rules would pass vacuously,
 * and saying so keeps the report honest about what is actually being checked. Kafka is emphatically
 * <em>on</em> - the consumer is this service's primary ingress, and the rules that keep a listener
 * from reaching a repository and a topic payload from being a JPA entity or a REST DTO are exactly
 * the ones worth enforcing here.
 *
 * <p>{@code domain-isolation} is enabled although this service keeps its business model in
 * {@code service.model} rather than a {@code domain} package, so the rule currently passes with
 * nothing to check. It is left on deliberately: it costs nothing, and it means a future
 * {@code domain} package inherits the constraint instead of having to remember to ask for it.
 */
@AnalyzeArchitecture(
        packagesOf = NotificationApplication.class,
        enable = "domain-isolation",
        disable = "storage")
class ArchitectureTest extends ArchitectureRulesTest {
}
