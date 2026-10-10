package ru.ludwigandreas.testsupport.local.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.local.LocalRun;
import ru.ludwigandreas.testsupport.local.integration.sample.AnnotationSeed;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApi;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApiStub;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleApplication;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleFixtures;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.integration.sample.Widget;
import ru.ludwigandreas.testsupport.local.integration.sample.WidgetRepository;

/**
 * Boots the sample application through the launcher, the way a developer's local application does.
 *
 * <p>No {@code @SpringBootTest} here on purpose. The claim under test is that
 * {@code LocalRun.from(main)} starts a service through its own entry point, and a test framework
 * building the context instead would be testing something else.
 *
 * <p>One thing these tests cannot assert is "no Kafka container is running": the containers are
 * per-JVM singletons that are never stopped, and another class in this suite starts the broker. What
 * is asserted instead is the observable half - an application that did not name Kafka is given no
 * broker address.
 */
class LocalRunIT {

    @Test
    @DisplayName("the service's own main starts, with its database, its stub and its fixtures")
    void startsTheServicesOwnMain() {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .fixtures(SampleFixtures.class)
                .run()) {
            // The application class itself is a bean: it was not replaced or re-declared.
            assertThat(context.getBean(SampleApplication.class)).isNotNull();

            String url = new JdbcTemplate(context.getBean(DataSource.class))
                    .execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
            assertThat(url).contains(String.valueOf(Containers.postgres().getFirstMappedPort()));

            assertThat(context.getBean(PartnerApi.class).thing("7")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
            assertThat(context.getBean(WidgetRepository.class).findAll()).containsExactly(
                    new Widget("alpha", AnnotationSeed.NOTE), new Widget("beta", AnnotationSeed.NOTE));

            assertThat(context.getEnvironment().getProperty("spring.kafka.bootstrap-servers"))
                    .as("Kafka was not named, so no broker address is published")
                    .isNull();
        }
    }

    @Test
    @DisplayName("naming Kafka publishes the started broker's address")
    void namingKafkaConnectsTheService() {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .kafka()
                .stubs(new SampleStubs())
                .run("--sample.kafka.enabled=true")) {
            assertThat(context.getEnvironment().getProperty("spring.kafka.bootstrap-servers"))
                    .isEqualTo(Containers.kafka().getBootstrapServers());
        }
    }

    @Test
    @DisplayName("no profile is activated unless the local application names it")
    void imposesNoProfile() {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .run()) {
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(context.getEnvironment().getProperty("sample.local-profile-applied")).isNull();
        }
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .profiles("local")
                .run()) {
            assertThat(context.getEnvironment().getProperty("sample.local-profile-applied")).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("a named backing service whose library is absent fails before anything starts")
    void missingLibraryFailsFirst() {
        AtomicBoolean mainCalled = new AtomicBoolean();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new FilteredClassLoader("org.testcontainers.kafka"));
        try {
            assertThatThrownBy(() -> LocalRun.from(args -> mainCalled.set(true)).postgres().kafka().run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KAFKA")
                    .hasMessageContaining("org.testcontainers:kafka");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        assertThat(mainCalled).as("the service's main must not have been reached").isFalse();
    }

    @Test
    @DisplayName("a main that starts no SpringApplication is reported, not silently accepted")
    void mainThatStartsNothing() {
        assertThatThrownBy(() -> LocalRun.from(args -> { }).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without starting a SpringApplication");
    }
}
