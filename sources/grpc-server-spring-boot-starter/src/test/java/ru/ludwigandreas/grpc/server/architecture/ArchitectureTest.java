package ru.ludwigandreas.grpc.server.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rule library applied to this module, {@code RuleGroup.GRPC} included: the one module
 * allowed to build a server is still checked for every other gRPC rule.
 *
 * <p>The service-shape rules are off because this is a library with no controllers, service layer,
 * repositories or mappers to describe. Two more, each for one class:
 * {@code tests.production-code-does-not-depend-on-test-frameworks} - {@code LudwigGrpcServerTest} is
 * a published test slice, and a test slice is test-framework code by definition; its dependencies
 * are optional so none reaches a production classpath. And
 * {@code exceptions.custom-exceptions-extend-the-base-exception} - the problem this module raises
 * does extend {@code LocalizedException}; the other exception is a startup refusal an operator
 * reads, which is never rendered as a problem and has no business carrying a problem status.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.grpc.server",
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
