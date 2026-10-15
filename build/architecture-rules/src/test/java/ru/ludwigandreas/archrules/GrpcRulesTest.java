package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.GrpcRules;

/**
 * Each gRPC rule is shown to fire on a deliberately violating fixture and to stay silent on the
 * correct shape beside it. A rule with no failing fixture has never been shown to fail.
 */
class GrpcRulesTest {

    private static ArchitectureRulesConfiguration bad() {
        return Fixtures.configurationFor("bad.grpc").build();
    }

    @Test
    @DisplayName("a hand-built channel and a hand-built server are reported, shaded builder included")
    void handBuiltTransportIsReported() {
        assertThat(Fixtures.failingRuleIds(bad())).contains(GrpcRules.NO_HAND_BUILT_TRANSPORT.value());

        assertThat(Fixtures.violationDescriptions(bad(), GrpcRules.NO_HAND_BUILT_TRANSPORT))
                .anyMatch(description -> description.contains("HandBuiltChannel"))
                .anyMatch(description -> description.contains("HandBuiltServer"))
                .noneMatch(description -> description.contains("DelegatingCatalogService"));
    }

    @Test
    @DisplayName("an unstereotyped service implementation is reported; the abstract generated base is not")
    void unstereotypedServiceIsReported() {
        List<String> violations = Fixtures.violationDescriptions(bad(), GrpcRules.SERVICE_IS_STEREOTYPED);

        assertThat(violations)
                .anyMatch(description -> description.contains("UnstereotypedCatalogService"))
                .noneMatch(description -> description.contains("CatalogImplBase implements"))
                .noneMatch(description -> description.contains("DelegatingCatalogService"))
                .noneMatch(description -> description.contains("SecuredCatalogService"));
    }

    @Test
    @DisplayName("a proxy-based annotation on a gRPC service is reported, on the class and on a method")
    void proxyAnnotationsAreReported() {
        List<String> violations =
                Fixtures.violationDescriptions(bad(), GrpcRules.NO_PROXY_ANNOTATIONS_ON_SERVICE);

        assertThat(violations)
                .anyMatch(description -> description.contains("SecuredCatalogService")
                        && description.contains("Transactional"))
                .anyMatch(description -> description.contains("SecuredCatalogService.getProduct")
                        && description.contains("PreAuthorize"))
                // The same annotations on the application service are correct: it is called through its proxy.
                .noneMatch(description -> description.contains("ProductLookup"))
                .noneMatch(description -> description.contains("DelegatingCatalogService"));
        assertThat(violations).allMatch(description -> description.contains("not applied on the gRPC call path"));
    }

    @Test
    @DisplayName("a gRPC service that reads a repository is reported; one that delegates is not")
    void repositoryAccessIsReported() {
        List<String> violations = Fixtures.violationDescriptions(bad(), GrpcRules.SERVICE_IS_A_THIN_ADAPTER);

        assertThat(violations)
                .anyMatch(description -> description.contains("RepositoryBackedCatalogService"))
                .noneMatch(description -> description.contains("DelegatingCatalogService"))
                .noneMatch(description -> description.contains("ProductLookup"));
    }

    @Test
    @DisplayName("a module with no gRPC type breaks none of the four rules")
    void moduleWithoutGrpcIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(
                        GrpcRules.NO_HAND_BUILT_TRANSPORT.value(),
                        GrpcRules.SERVICE_IS_STEREOTYPED.value(),
                        GrpcRules.NO_PROXY_ANNOTATIONS_ON_SERVICE.value(),
                        GrpcRules.SERVICE_IS_A_THIN_ADAPTER.value());
    }

    @Test
    @DisplayName("the group is on by default")
    void groupIsEnabledByDefault() {
        assertThat(ArchitectureRules.suite(bad()).rules())
                .extracting(rule -> rule.id().value())
                .contains(GrpcRules.NO_HAND_BUILT_TRANSPORT.value());
    }
}
