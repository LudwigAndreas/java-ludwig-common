package ru.ludwigandreas.testsupport.local.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixtures;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * Proves that having this module on a test classpath changes nothing by itself.
 *
 * <p>This is an ordinary {@code @SpringBootTest} of the sample that uses neither the launcher nor
 * {@code @WithPartnerStubs}. If the module ever registered an auto-configuration, or its context
 * customizer acted without its annotation, every integration test of every service that depends on
 * it would quietly gain stubs and overridden addresses.
 */
@SampleContext
class InertOnClasspathIT {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("an ordinary context has no stubs, no fixtures runner and no launcher beans")
    void addsNoBeans() {
        assertThat(context.getBeanProvider(StubServers.class).getIfAvailable()).isNull();
        assertThat(context.getBeanProvider(SeedFixtures.class).getIfAvailable()).isNull();
        assertThat(context.containsBean("ludwigLocalRunPlan")).isFalse();
        assertThat(context.containsBean("ludwigLocalFixtureRunner")).isFalse();
    }

    @Test
    @DisplayName("an ordinary context keeps the address the service's own configuration names")
    void overridesNoProperty() {
        assertThat(context.getEnvironment().getProperty("ludwig.rest-client.clients.partner.base-url"))
                .isEqualTo("https://partner.invalid/api");
    }

    @Test
    @DisplayName("the module ships no auto-configuration")
    void shipsNoAutoConfiguration() {
        // The module's own output directory, which is what becomes its jar.
        Path imports = Path.of("target", "classes", "META-INF", "spring",
                "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(Files.exists(Path.of("target", "classes"))).isTrue();
        assertThat(Files.exists(imports)).isFalse();
    }
}
