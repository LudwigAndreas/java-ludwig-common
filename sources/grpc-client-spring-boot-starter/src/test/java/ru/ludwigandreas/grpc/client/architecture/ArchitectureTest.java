package ru.ludwigandreas.grpc.client.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rule library applied to this module, {@code RuleGroup.GRPC} included.
 *
 * <p>The service-shape rules are off because this is a library with no controllers, service layer,
 * repositories or mappers to describe. Two more, each for a stated reason:
 * {@code tests.production-code-does-not-depend-on-test-frameworks} - {@code LudwigGrpcClientTest}
 * is a published test slice, test-framework code by definition, with optional dependencies; and
 * {@code exceptions.custom-exceptions-extend-the-base-exception} - the client failures are thrown at
 * a caller who catches them, and are rendered through this module's own mapper, not as a
 * {@code LocalizedException}'s own problem.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.grpc.client",
        disable = {
                "layering",
                "persistence.repositories-are-used-only-by-services",
                "persistence.persistence-context-is-confined",
                "spring.transactional-methods-are-in-the-service-layer",
                "web.controllers-do-not-call-controllers",
                "rest-paths",
                "mappers.mappers-are-mapstruct-interfaces",
                "tests.production-code-does-not-depend-on-test-frameworks",
                "exceptions.custom-exceptions-extend-the-base-exception"
        })
class ArchitectureTest extends ArchitectureRulesTest {
}
