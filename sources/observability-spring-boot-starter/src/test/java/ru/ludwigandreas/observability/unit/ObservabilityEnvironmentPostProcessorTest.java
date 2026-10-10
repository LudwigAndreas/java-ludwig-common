package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.observability.config.ObservabilityEnvironmentPostProcessor;

/** The defaults that make a service observable without configuring anything - and never override it. */
class ObservabilityEnvironmentPostProcessorTest {

    private final ObservabilityEnvironmentPostProcessor postProcessor = new ObservabilityEnvironmentPostProcessor();

    @Test
    void splitsLivenessFromReadinessSoAnUnreadyServiceIsNotKilledAndRestarted() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.health.livenessstate.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.health.readinessstate.enabled")).isEqualTo("true");
    }

    @Test
    void exposesOperationalEndpointsButNothingThatDisclosesConfiguration() {
        String exposed = process(new MockEnvironment()).getProperty("management.endpoints.web.exposure.include");

        assertThat(exposed).contains("health", "prometheus", "loggers");
        // env, configprops and heapdump disclose credentials and memory contents; a starter must not
        // be the reason they became reachable.
        assertThat(exposed).doesNotContain("env", "configprops", "heapdump", "threaddump");
    }

    @Test
    void turnsOnKafkaObservationWhichSpringShipsDisabled() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("spring.kafka.template.observation-enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("true");
    }

    @Test
    void enablesGracefulShutdownSoADeployDoesNotLookLikeAnIncident() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    }

    @Test
    void derivesTheServiceNameFromTheApplicationName() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("spring.application.name", "catalog"));

        assertThat(environment.getProperty("ludwig.observability.service.name")).isEqualTo("catalog");
    }

    @Test
    void publishesTheIdentityAsResourceAttributesUsingBracketedMapKeys() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("spring.application.name", "catalog"));

        // Bracket notation keeps the dots inside the map key; a dotted suffix would leave the binder
        // guessing where the property path ends and the key begins.
        assertThat(environment.getProperty("management.opentelemetry.resource-attributes[service.name]"))
                .isEqualTo("catalog");
    }

    @Test
    void neverOverridesAValueTheServiceHasSetItself() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(
                "spring.application.name", "catalog",
                "ludwig.observability.service.name", "catalog-api",
                "server.shutdown", "immediate")));

        process(environment);

        assertThat(environment.getProperty("server.shutdown")).isEqualTo("immediate");
        assertThat(environment.getProperty("ludwig.observability.service.name")).isEqualTo("catalog-api");
        // And the resource attributes follow the value the service chose, rather than the derived one -
        // otherwise the traces and the metrics would describe two different services.
        assertThat(environment.getProperty("management.opentelemetry.resource-attributes[service.name]"))
                .isEqualTo("catalog-api");
    }

    @Test
    void addsNothingWhenTheModuleIsDisabled() {
        MockEnvironment environment = process(
                new MockEnvironment().withProperty("ludwig.observability.enabled", "false"));

        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isNull();
    }

    @Test
    void isIdempotentWhenTheSameEnvironmentIsProcessedTwice() {
        MockEnvironment environment = process(new MockEnvironment());
        long before = environment.getPropertySources().stream().count();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().stream().count()).isEqualTo(before);
    }

    @Test
    void publishesTheBuildProvenanceReadFromThePackagedResources(@TempDir Path classpath) throws IOException {
        write(classpath, "git.properties", """
                git.branch=master
                git.commit.id=c1fc5b81ecfa20e1b3418002a5ace90473d6734c
                git.commit.id.abbrev=c1fc5b8
                git.dirty=true
                """);
        write(classpath, "META-INF/build-info.properties", """
                build.time=2026-10-10T00\\:00\\:00Z
                build.ci.build-number=4711
                """);

        MockEnvironment environment = process(new MockEnvironment(), classpath);

        assertThat(environment.getProperty("ludwig.observability.build.commit-id"))
                .isEqualTo("c1fc5b81ecfa20e1b3418002a5ace90473d6734c");
        assertThat(environment.getProperty("ludwig.observability.build.abbreviated-commit-id")).isEqualTo("c1fc5b8");
        assertThat(environment.getProperty("ludwig.observability.build.branch")).isEqualTo("master");
        assertThat(environment.getProperty("ludwig.observability.build.timestamp")).isEqualTo("2026-10-10T00:00:00Z");
        assertThat(environment.getProperty("ludwig.observability.build.ci-build-number")).isEqualTo("4711");
        assertThat(environment.getProperty("ludwig.observability.build.dirty")).isEqualTo("true");
    }

    @Test
    void omitsAnUnresolvedBuildFieldRatherThanPublishingItEmpty(@TempDir Path classpath) throws IOException {
        // A local build from a checkout: there is a commit, and there is no CI build number.
        write(classpath, "git.properties", "git.commit.id.abbrev=c1fc5b8\n");

        MockEnvironment environment = process(new MockEnvironment(), classpath);

        assertThat(environment.getProperty("ludwig.observability.build.abbreviated-commit-id")).isEqualTo("c1fc5b8");
        // containsProperty, not getProperty: an empty string would also read back as "nothing there"
        // to a careless caller, and the point is that the key is not published at all.
        assertThat(environment.containsProperty("ludwig.observability.build.ci-build-number")).isFalse();
        assertThat(environment.containsProperty("ludwig.observability.build.branch")).isFalse();
        assertThat(environment.containsProperty("ludwig.observability.build.dirty")).isFalse();
    }

    @Test
    void keepsBuildProvenanceOutOfTheResourceAttributesAndSoOutOfTheMetricTags(@TempDir Path classpath)
            throws IOException {
        write(classpath, "git.properties", "git.branch=master\ngit.commit.id.abbrev=c1fc5b8\ngit.dirty=true\n");

        MockEnvironment environment = process(
                new MockEnvironment().withProperty("spring.application.name", "catalog"), classpath);

        MapPropertySource defaults = (MapPropertySource) environment.getPropertySources()
                .get("ludwig-observability-defaults");
        // The resource attributes are what become the dimensions a backend groups by. The service
        // name has to be there, or this would pass on an empty set; nothing of the build may be.
        assertThat(defaults.getSource().keySet())
                .filteredOn(key -> key.startsWith("management.opentelemetry.resource-attributes"))
                .contains("management.opentelemetry.resource-attributes[service.name]")
                .allSatisfy(key -> assertThat(key).doesNotContain("commit", "branch", "dirty", "build"));
    }

    @Test
    void defaultsToStructuredLoggingWhenNoProfileIsActive() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("ludwig.observability.logging.json.enabled")).isEqualTo("true");
    }

    @Test
    void defaultsToHumanReadableLoggingUnderTheLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        process(environment);

        assertThat(environment.getProperty("ludwig.observability.logging.json.enabled")).isEqualTo("false");
    }

    @Test
    void letsAnExplicitSettingWinOverTheLocalProfileDefault() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(
                "ludwig.observability.logging.json.enabled", "true")));

        process(environment);

        assertThat(environment.getProperty("ludwig.observability.logging.json.enabled")).isEqualTo("true");
    }

    @Test
    void keepsStructuredLoggingUnderAnyProfileThatIsNotLocal() {
        MockEnvironment environment = new MockEnvironment();
        // "localhost" and "local-eu" are here on purpose: the match is on the profile's whole name,
        // and a prefix match would hand a production tier called local-eu a text console.
        environment.setActiveProfiles("prod", "localhost", "local-eu");

        process(environment);

        assertThat(environment.getProperty("ludwig.observability.logging.json.enabled")).isEqualTo("true");
    }

    @Test
    void switchesTheBannerOffUnderTheStructuredFormatBecauseItNeverPassesThroughAnEncoder() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("off");
    }

    @Test
    void leavesTheBannerAloneUnderTheHumanReadableFormat() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        process(environment);

        assertThat(environment.containsProperty("spring.main.banner-mode")).isFalse();
    }

    @Test
    void followsTheFormatTheServiceChoseAndNotTheProfileWhenDecidingAboutTheBanner() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(
                "ludwig.observability.logging.json.enabled", "true")));

        process(environment);

        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("off");
    }

    @Test
    void leavesAnExistingManualLocalOverrideExactlyAsItWas() {
        // What the service template told every service to write before the default existed.
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.getPropertySources().addFirst(new MapPropertySource("application-local", Map.of(
                "ludwig.observability.logging.json.enabled", "false")));

        process(environment);

        assertThat(environment.getProperty("ludwig.observability.logging.json.enabled")).isEqualTo("false");
    }

    private MockEnvironment process(MockEnvironment environment) {
        postProcessor.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    /** Processes with an application whose class loader sees {@code classpath} and nothing else. */
    private MockEnvironment process(MockEnvironment environment, Path classpath) throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classpath.toUri().toURL()}, null)) {
            postProcessor.postProcessEnvironment(environment, new SpringApplication(new DefaultResourceLoader(loader)));
        }
        return environment;
    }

    private static void write(Path classpath, String name, String content) throws IOException {
        Path file = classpath.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
