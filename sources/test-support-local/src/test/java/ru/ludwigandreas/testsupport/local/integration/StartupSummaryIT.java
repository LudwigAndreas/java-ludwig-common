package ru.ludwigandreas.testsupport.local.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.local.LocalRun;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApiStub;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleApplication;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleFixtures;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * Proves the start-up summary carries what a developer needs without reading source.
 *
 * <p>Every address in it is allocated at start-up, so the test compares the log against the running
 * things rather than against fixed text.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupSummaryIT {

    @Test
    @DisplayName("the summary lists the database, each stub's two URLs and fault, and the fixtures that ran")
    void listsWhatWasStarted(CapturedOutput output) {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .fixtures(SampleFixtures.class)
                .run()) {
            RunningStub stub = context.getBean(StubServers.class).stub("partner");

            assertThat(output)
                    .contains("Local run is ready.")
                    .contains(Containers.postgres().getJdbcUrl())
                    .contains(stub.baseUrl())
                    .contains(stub.adminUrl())
                    .contains("fault none")
                    .contains("curl -X PUT " + stub.adminUrl() + "/scenarios/fault/state")
                    .contains("Seed fixtures (mode reset)")
                    .contains("WidgetSeed, AnnotationSeed");
        }
    }

    @Test
    @DisplayName("a fault set at start-up and a switched-off fixture mode are both shown")
    void showsFaultAndFixtureMode(CapturedOutput output) {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .fixtures(SampleFixtures.class)
                .run("--ludwig.local.stubs.partner.fault=unavailable", "--ludwig.local.fixtures.mode=none")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(output)
                    .contains("fault unavailable")
                    .contains("Seed fixtures (mode none)")
                    .contains("none ran");
        }
    }

    @Test
    @DisplayName("a stub's own lines are printed under its entry")
    void printsAStubsOwnLines(CapturedOutput output) {
        StubCatalog catalog = () -> List.of(new PartnerApiStub() {
            @Override
            public List<String> summaryLines(RunningStub running) {
                return List.of("try it:       curl " + running.baseUrl() + "/things/1");
            }
        });
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(catalog)
                .run()) {
            RunningStub stub = context.getBean(StubServers.class).stub("partner");

            assertThat(output).contains("try it:       curl " + stub.baseUrl() + "/things/1");
        }
    }

    @Test
    @DisplayName("a pass-through client is listed with the address it calls")
    void listsPassThroughClients(CapturedOutput output) {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .run("--ludwig.local.stubs.partner.mode=passthrough")) {
            assertThat(context.isRunning()).isTrue();
            assertThat(output)
                    .contains("Pass-through clients")
                    .contains("https://partner.invalid/api");
        }
    }
}
