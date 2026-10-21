package ru.ludwigandreas.messaging.db.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * This module against the platform's rules. Disabled rule groups are the ones that describe a service's
 * layers, web surface or persistence model, none of which a transport library has - the same set
 * messaging-spring-boot-starter disables, for the same reason.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.messaging.db",
        disable = {
                "layering",
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
