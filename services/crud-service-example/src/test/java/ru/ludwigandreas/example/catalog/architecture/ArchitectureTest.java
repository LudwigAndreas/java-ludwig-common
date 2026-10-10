package ru.ludwigandreas.example.catalog.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

import ru.ludwigandreas.example.catalog.CatalogApplication;

/**
 * The whole architecture test of this service.
 *
 * <p>Every rule of the shared library becomes its own test here, named by its id, so a violation
 * shows up in the build report as, say, {@code persistence.entities-reside-in-entity-packages}
 * rather than as one long assertion failure.
 *
 * <p>Kafka and object storage are switched off because this service has neither: the rules would
 * pass vacuously, but saying so keeps the report honest about what is actually being checked.
 *
 * <p>{@code logging} is on by default and is named anyway, for the opposite reason: this is the
 * reference service, and a group a reader has to know is implicit is one the next service copies
 * without. It holds that nothing here implements a log encoder, restates the build identity, or
 * asks git at runtime.
 */
@AnalyzeArchitecture(
        packagesOf = CatalogApplication.class,
        enable = {"domain-isolation", "logging"},
        disable = {"kafka", "storage"})
class ArchitectureTest extends ArchitectureRulesTest {
}
