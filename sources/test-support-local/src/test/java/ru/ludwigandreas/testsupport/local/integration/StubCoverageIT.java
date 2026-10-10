package ru.ludwigandreas.testsupport.local.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.testsupport.local.LocalRun;
import ru.ludwigandreas.testsupport.local.LocalRunConfiguration;
import ru.ludwigandreas.testsupport.local.LocalRunPlan;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApiStub;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleApplication;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubAddress;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * Proves the launcher refuses to start when the service's rest clients and its stubs do not match.
 *
 * <p>{@code ghost} is a client that exists only as configuration - no annotated interface - which is
 * the case the build-time rule cannot see and this check exists for.
 */
class StubCoverageIT {

    private static final String GHOST_CLIENT = "--ludwig.rest-client.clients.ghost.base-url=http://localhost:1";

    @Test
    @DisplayName("a rest client with no stub and no pass-through stops the launch, by name")
    void clientWithoutStub() {
        assertThatThrownBy(() -> LocalRun.from(SampleApplication::main).postgres().run())
                .hasStackTraceContaining("Rest client 'partner' has no partner stub and no pass-through");
    }

    @Test
    @DisplayName("a client that is only a configured name is checked too")
    void configuredOnlyClientWithoutStub() {
        assertThatThrownBy(() -> LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .run(GHOST_CLIENT))
                .hasStackTraceContaining("Rest client 'ghost' has no partner stub and no pass-through");
    }

    @Test
    @DisplayName("a client declared pass-through in the catalog keeps its address and is accepted")
    void declaredPassThrough() {
        StubCatalog withPassThrough = new StubCatalog() {
            @Override
            public List<PartnerStub> stubs() {
                return List.of(new PartnerApiStub());
            }

            @Override
            public Set<String> passThrough() {
                return Set.of("ghost");
            }
        };
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(withPassThrough)
                .run(GHOST_CLIENT)) {
            assertThat(context.getEnvironment().getProperty("ludwig.rest-client.clients.ghost.base-url"))
                    .isEqualTo("http://localhost:1");
        }
    }

    @Test
    @DisplayName("a stubbed client can be switched to pass-through for one launch")
    void passThroughForOneLaunch() {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .run("--ludwig.local.stubs.partner.mode=passthrough")) {
            StubServers stubs = context.getBean(StubServers.class);
            assertThat(stubs.stubs()).as("the stub must not have been started").isEmpty();
            assertThat(stubs.passThrough()).containsExactly("partner");
            assertThat(context.getEnvironment().getProperty("ludwig.rest-client.clients.partner.base-url"))
                    .isEqualTo("https://partner.invalid/api");
        }
    }

    @Test
    @DisplayName("a stub for a client that does not exist stops the launch, naming the stub")
    void stubForUnknownClient() {
        StubCatalog withDeadStub = () -> List.of(new PartnerApiStub(), new VanishedStub());

        assertThatThrownBy(() -> LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(withDeadStub)
                .run())
                .hasStackTraceContaining(VanishedStub.class.getName())
                .hasStackTraceContaining("'vanished', which does not exist");
    }

    @Test
    @DisplayName("a stub addressed by a service property is wired through that property")
    void propertyAddressedStubIsWired() {
        StubCatalog catalog = () -> List.of(new PartnerApiStub(), new SecondPartnerStub());
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(catalog)
                .run("--" + SecondPartnerStub.PROPERTY + "=https://second.invalid/api")) {
            assertThat(context.getEnvironment().getProperty(SecondPartnerStub.PROPERTY))
                    .isEqualTo(context.getBean(StubServers.class).stub("second").baseUrl());
        }
    }

    @Test
    @DisplayName("a stub whose address property nothing else sets stops the launch, naming stub and property")
    void propertyAddressedStubForAnUnsetProperty() {
        StubCatalog catalog = () -> List.of(new PartnerApiStub(), new SecondPartnerStub());

        assertThatThrownBy(() -> LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(catalog)
                .run())
                .hasStackTraceContaining(SecondPartnerStub.class.getName())
                .hasStackTraceContaining(SecondPartnerStub.PROPERTY + ", which the service does not configure");
    }

    @Test
    @DisplayName("an empty value counts as not configured")
    void emptyPropertyIsNotConfigured() {
        StubCatalog catalog = () -> List.of(new PartnerApiStub(), new SecondPartnerStub());

        assertThatThrownBy(() -> LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(catalog)
                .run("--" + SecondPartnerStub.PROPERTY + "="))
                .hasStackTraceContaining(SecondPartnerStub.PROPERTY + ", which the service does not configure");
    }

    @Test
    @DisplayName("a stub with no address property starts and overrides nothing")
    void addresslessStubStarts() {
        StubCatalog catalog = () -> List.of(new PartnerApiStub(), new SinkStub());
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(catalog)
                .run()) {
            StubServers stubs = context.getBean(StubServers.class);
            assertThat(stubs.stub("sink").baseUrl()).startsWith("http://127.0.0.1:");
            assertThat(stubs.properties().keySet())
                    .as("only the rest-client stub publishes anything")
                    .containsExactlyInAnyOrder("ludwig.rest-client.clients.partner.base-url",
                            PartnerApiStub.TOKEN_URL_PROPERTY);
        }
    }

    @Test
    @DisplayName("without the rest-client starter the configuration starts and skips only the client check")
    void startsWithoutTheRestClientStarter() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(RestClientRegistry.class))
                .withBean(LocalRunPlan.class, () -> new LocalRunPlan(Set.of()))
                .withUserConfiguration(LocalRunConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("ludwigLocalStubCoverageCheck");
                    assertThat(context).hasBean("ludwigLocalStubAddressCheck");
                    assertThat(context).hasBean("ludwigLocalFixtureRunner");
                });
    }

    @Test
    @DisplayName("without the rest-client starter a dead property-addressed stub is still refused")
    void deadStubIsRefusedWithoutTheRestClientStarter() {
        StubCatalog catalog = () -> List.of(new SecondPartnerStub());
        try (StubServers started = StubServers.start(catalog, new MockEnvironment())) {
            new ApplicationContextRunner()
                    .withClassLoader(new FilteredClassLoader(RestClientRegistry.class))
                    .withBean(LocalRunPlan.class, () -> new LocalRunPlan(Set.of()))
                    .withBean(StubServers.class, () -> started)
                    .withUserConfiguration(LocalRunConfiguration.class)
                    .run(context -> assertThat(context).getFailure()
                            .hasStackTraceContaining(SecondPartnerStub.PROPERTY));
        }
    }

    /** A stand-in for a partner the service reaches through a property of its own. */
    static class SecondPartnerStub implements PartnerStub {

        static final String PROPERTY = "sample.second-partner.base-url";

        @Override
        public String name() {
            return "second";
        }

        @Override
        public StubAddress address() {
            return StubAddress.property(PROPERTY);
        }

        @Override
        public void register(WireMockServer server) {
            // The tests here are about wiring, not answers.
        }
    }

    /** A stand-in nothing in configuration points at, such as a webhook receiver. */
    static class SinkStub implements PartnerStub {

        @Override
        public String name() {
            return "sink";
        }

        @Override
        public StubAddress address() {
            return StubAddress.none();
        }

        @Override
        public void register(WireMockServer server) {
            // Nothing to answer in these tests.
        }
    }

    /** A stand-in for a client the service no longer has - what a rename leaves behind. */
    static class VanishedStub implements PartnerStub {

        @Override
        public String name() {
            return "vanished";
        }

        @Override
        public void register(WireMockServer server) {
            // Nothing calls it, which is the point.
        }
    }
}
