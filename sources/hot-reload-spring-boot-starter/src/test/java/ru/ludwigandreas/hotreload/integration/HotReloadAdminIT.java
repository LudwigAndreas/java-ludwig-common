package ru.ludwigandreas.hotreload.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.hotreload.admin.service.HotReloadReloadControl;
import ru.ludwigandreas.hotreload.binding.HotReloadTypedConfigFactory;
import ru.ludwigandreas.hotreload.binding.RefreshableConfig;
import ru.ludwigandreas.hotreload.core.ReloadableSourceRegistry;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.hotreload.properties.PropertyFileSource;
import ru.ludwigandreas.job.core.control.ControlStore;
import ru.ludwigandreas.job.core.control.ReplicaControlPoller;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code hotreload} area over job-core's real channel: two replicas against one PostgreSQL - replica A is this
 * context, replica B a second poller with its own coordinator over the same file - and a properties file whose
 * watcher is held off (one-hour debounce), so the control is the only thing that reloads it.
 */
@SpringBootTest(classes = HotReloadAdminIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.jwt.enabled=false",
            "ludwig.web.i18n.configure-validator=false",
            "spring.liquibase.change-log=classpath:db/changelog/job-core/job-core-changelog.xml",
            "ludwig.job-core.owner=replica-a",
            "ludwig.job-core.control.poll-interval=PT1H",
            "ludwig.hotreload.file-watch.debounce=PT1H",
            // A fixed path, not @DynamicPropertySource: the starter's EnvironmentPostProcessor reads it first.
            "ludwig.hotreload.files[0].path=" + HotReloadAdminIT.PATH,
            "ludwig.security.roles.OPS.operations[replica.instance.read]=ALL",
            "ludwig.security.roles.OPS.operations[replica.command.read]=ALL",
            "ludwig.security.roles.OPS.operations[hotreload.source.read]=ALL",
            "ludwig.security.roles.OPS.operations[hotreload.source.reload]=ALL"})
@AutoConfigureMockMvc
class HotReloadAdminIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PATH = "target/hotreload-admin-it/app.properties";
    private static final Path FILE = file();
    private static final String INITIAL = "demo.retries=1\ndemo.password=hunter2\n";

    private static Path file() {
        try {
            Path file = Path.of(PATH);
            Files.createDirectories(file.getParent());
            Files.writeString(file, INITIAL);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A typed configuration with a constraint the bad edit breaks. */
    public static class DemoConfig {

        @Min(1)
        private int retries;
        private String password;

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        @Bean
        DataSource dataSource() {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(Containers.postgres().getJdbcUrl());
            source.setUser(Containers.postgres().getUsername());
            source.setPassword(Containers.postgres().getPassword());
            return source;
        }

        @Bean
        RefreshableConfig<DemoConfig> demoConfig(HotReloadTypedConfigFactory factory) {
            return factory.create("demo", DemoConfig.class);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReplicaControlPoller replicaA;

    @Autowired
    private SourceReloadCoordinator coordinatorA;

    @Autowired
    private RefreshableConfig<DemoConfig> demo;

    private SourceReloadCoordinator coordinatorB;
    private ReplicaControlPoller replicaB;
    private ThreadPoolTaskScheduler schedulerB;

    @BeforeEach
    void twoReplicas() throws IOException {
        Files.writeString(FILE, INITIAL);
        coordinatorA.reloadOutcome(new PropertyFileSource(FILE, ""));
        new JdbcTemplate(dataSource).execute("DELETE FROM job_control_command; DELETE FROM job_instance");
        PropertyFileSource sourceB = new PropertyFileSource(FILE, "");
        coordinatorB = new SourceReloadCoordinator();
        coordinatorB.reloadOutcome(sourceB);
        ReloadableSourceRegistry registryB = new ReloadableSourceRegistry();
        registryB.register(ReloadableSourceRegistry.Kind.FILE, sourceB, coordinatorB);
        schedulerB = new ThreadPoolTaskScheduler();
        schedulerB.initialize();
        replicaB = new ReplicaControlPoller(schedulerB, new ControlStore(dataSource), "replica-b",
                Map.of(HotReloadReloadControl.NAME, new HotReloadReloadControl(registryB)), JSON,
                new ReplicaControlPoller.ControlSettings(Duration.ofHours(1), Duration.ofSeconds(5),
                        Duration.ofDays(7)), new JdbcRunLock(dataSource, "replica-b"));
        replicaB.runNow();
        replicaA.runNow();
    }

    @AfterEach
    void stopReplicaB() {
        schedulerB.shutdown();
    }

    private static RequestPostProcessor ops() {
        return TestPrincipalBuilder.user("olga").roles("OPS").postProcessor();
    }

    private static String sourceId() {
        return new PropertyFileSource(FILE, "").id();
    }

    private MvcResult reload(String source) throws Exception {
        return mvc.perform(post("/admin/v1/replicas/controls/hotreloadreload").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("source", source))).with(ops())).andReturn();
    }

    private JsonNode runOnBoth(MvcResult submitted) throws Exception {
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        replicaA.runNow();
        replicaB.runNow();
        return JSON.readTree(mvc.perform(get(submitted.getResponse().getHeader("Location")).with(ops()))
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> outcomes(JsonNode command) {
        return command.path("targets").findValuesAsText("outcome");
    }

    @Test
    @DisplayName("the sources are listed with their kind and last reload here, and none of their values")
    void listsSources() throws Exception {
        String body = mvc.perform(get("/admin/v1/hotreload/sources").with(ops())).andReturn().getResponse()
                .getContentAsString();

        JsonNode sources = JSON.readTree(body);
        assertThat(sources.findValuesAsText("id")).containsExactly(sourceId());
        assertThat(sources.get(0).path("kind").asText()).isEqualTo("FILE");
        assertThat(sources.get(0).has("lastReloadedHere")).isTrue();
        assertThat(body).doesNotContain("hunter2").doesNotContain("retries");
    }

    @Test
    @DisplayName("a changed source is reloaded on both replicas, and the new value is in use on each")
    void reloadsEverywhere() throws Exception {
        Files.writeString(FILE, "demo.retries=3\ndemo.password=rotated\n");

        JsonNode command = runOnBoth(reload(sourceId()));

        assertThat(command.path("operationEnvelope").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(outcomes(command)).containsExactly("reloaded", "reloaded");
        assertThat(demo.get().getPassword()).isEqualTo("rotated");
        assertThat(coordinatorB.lastKnownGood(sourceId())).containsEntry("demo.password", "rotated");
        assertThat(outcomes(runOnBoth(reload(sourceId())))).containsExactly("unchanged", "unchanged");
    }

    @Test
    @DisplayName("content the typed configuration rejects is reported rejected, the command still SUCCEEDED, "
            + "and the last-known-good value stays in use")
    void rejectedKeepsLastKnownGood() throws Exception {
        Files.writeString(FILE, "demo.retries=0\ndemo.password=hunter2\n");

        JsonNode command = runOnBoth(reload(sourceId()));

        assertThat(command.path("operationEnvelope").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(command.path("targets").findValuesAsText("outcome")).contains("rejected");
        assertThat(demo.get().getRetries()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown source is a 400 and nothing is broadcast")
    void unknownSource() throws Exception {
        assertThat(reload("no-such-source").getResponse().getStatus()).isEqualTo(400);
    }
}
