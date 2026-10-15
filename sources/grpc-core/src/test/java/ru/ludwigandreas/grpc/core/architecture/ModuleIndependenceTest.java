package ru.ludwigandreas.grpc.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps this module what its POM says it is: the shared vocabulary, with no transport, no servlet
 * and no platform module other than web-core behind it.
 *
 * <p>Both gRPC starters depend on this module and a service may carry only one of them, so anything
 * reached from here is on the classpath of every service that speaks gRPC at all. The rule is an
 * allow-list rather than a ban-list for that reason: a new dependency has to be added here
 * deliberately, with a line saying why, instead of arriving unnoticed.
 */
class ModuleIndependenceTest {

    private static final String[] OWN_PACKAGES = {
        "ru.ludwigandreas.grpc.core..",
        // Generated from this module's own operation.proto.
        "ru.ludwigandreas.operation.grpc.v1..",
    };

    private final JavaClasses mainClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ru.ludwigandreas.grpc.core", "ru.ludwigandreas.operation.grpc.v1");

    @Test
    @DisplayName("the only platform module reached from here is web-core")
    void dependsOnWebCoreOnly() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage(OWN_PACKAGES)
                .should().dependOnClassesThat()
                .resideOutsideOfPackages(
                        "ru.ludwigandreas.grpc.core..", "ru.ludwigandreas.operation.grpc.v1..",
                        "ru.ludwigandreas.webcore..",
                        "java..",
                        // The gRPC API, the protobuf runtime and the google.rpc error model.
                        "io.grpc", "io.grpc.protobuf..", "io.grpc.stub..",
                        "com.google.protobuf..", "com.google.rpc..",
                        // ProblemDetail and nothing else of Spring's.
                        "org.springframework.http..",
                        // MapStruct's annotations only, for ProtobufMapperConfig: the one mapper
                        // configuration both a server adapter and a client caller use. The
                        // dependency is optional, and the annotations have no runtime behaviour.
                        "org.mapstruct..")
                .as("grpc-core depends on web-core, the gRPC API and the protobuf runtime, and on nothing else");

        rule.check(mainClasses);
    }

    @Test
    @DisplayName("no transport and no servlet type is reachable from here")
    void hasNoTransport() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage(OWN_PACKAGES)
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.grpc.netty..", "io.netty..", "io.grpc.inprocess..", "io.grpc.okhttp..",
                        "jakarta.servlet..", "javax.servlet..")
                .as("grpc-core carries no gRPC transport and no servlet dependency");

        rule.check(mainClasses);
    }
}
