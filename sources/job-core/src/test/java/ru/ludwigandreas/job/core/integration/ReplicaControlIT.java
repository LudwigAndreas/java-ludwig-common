package ru.ludwigandreas.job.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.admin.JobAdminOperations;
import ru.ludwigandreas.job.core.control.ControlStore;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.job.core.control.ReplicaControlPoller;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Two replicas against one PostgreSQL: replica A is this context, serving the HTTP surface and polling with its own
 * poller; replica B is a second poller over the same tables with its own owner id and its own instance of the
 * control. Polls are driven explicitly ({@code runNow}) so every assertion is about one known pass.
 */
@SpringBootTest(classes = ReplicaControlIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.jwt.enabled=false",
            "ludwig.web.i18n.configure-validator=false",
            "spring.liquibase.change-log=classpath:db/changelog/job-core/job-core-changelog.xml",
            "ludwig.job-core.owner=replica-a",
            "ludwig.job-core.control.poll-interval=PT1H",
            "ludwig.admin.grants.jobs.read[0]=PLATFORM_SUPPORT",
            "ludwig.admin.grants.jobs.read[1]=PLATFORM_ADMIN",
            "ludwig.admin.grants.jobs.operate[0]=PLATFORM_ADMIN",
            "ludwig.admin.grants.jobs.privileged[0]=PLATFORM_ADMIN",
            "ludwig.admin.grants.replicas.read[0]=PLATFORM_SUPPORT",
            "ludwig.admin.grants.replicas.read[1]=PLATFORM_ADMIN",
            "ludwig.admin.grants.replicas.operate[0]=PLATFORM_ADMIN",
            "ludwig.admin.grants.replicas.privileged[0]=PLATFORM_ADMIN"})
@AutoConfigureMockMvc
class ReplicaControlIT {

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AdminOperation OPERATE = AdminOperation.of(JobAdminOperations.OPERATE, "k");
    private static final AdminOperation PRIVILEGED = AdminOperation.of(JobAdminOperations.PRIVILEGED, "k");

    /**
     * The probe control's arguments.
     *
     * @param label      echoed back as the per-replica result
     * @param privileged whether to declare the privileged tier
     */
    record ProbeArgs(@NotBlank String label, boolean privileged) {
    }

    /** Records each application; one instance per replica, so the two can be told apart and made to differ. */
    static final class Probe implements ReplicaControl<ProbeArgs> {
        final List<String> applied = new CopyOnWriteArrayList<>();
        final String replica;
        volatile boolean failing;

        Probe(String replica) {
            this.replica = replica;
        }

        @Override
        public String name() {
            return "probe";
        }

        @Override
        public Class<ProbeArgs> argumentType() {
            return ProbeArgs.class;
        }

        @Override
        public AdminOperation operationFor(ProbeArgs arguments) {
            return arguments.privileged() ? PRIVILEGED : OPERATE;
        }

        @Override
        public Set<AdminOperation> possibleOperations() {
            return Set.of(OPERATE, PRIVILEGED);
        }

        @Override
        public Optional<Object> apply(ProbeArgs arguments) {
            if (failing) {
                throw new IllegalStateException("probe refused on " + replica);
            }
            applied.add(arguments.label());
            return Optional.of(Map.of("replica", replica, "label", arguments.label()));
        }
    }

    static final Probe ON_A = new Probe("replica-a");
    static final Probe ON_B = new Probe("replica-b");

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
        ReplicaControl<ProbeArgs> probe() {
            return ON_A;
        }

        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReplicaControlPoller replicaA;

    private ReplicaControlPoller replicaB;
    private ThreadPoolTaskScheduler schedulerB;

    @BeforeEach
    void twoReplicas() {
        AUDIT.clear();
        new JdbcTemplate(dataSource).execute("DELETE FROM job_control_command; DELETE FROM job_instance");
        ON_A.applied.clear();
        ON_B.applied.clear();
        ON_A.failing = false;
        ON_B.failing = false;
        schedulerB = new ThreadPoolTaskScheduler();
        schedulerB.initialize();
        replicaB = new ReplicaControlPoller(schedulerB, new ControlStore(dataSource), "replica-b",
                Map.of("probe", ON_B), JSON, new ReplicaControlPoller.ControlSettings(Duration.ofHours(1),
                        Duration.ofSeconds(5), Duration.ofDays(7)), new JdbcRunLock(dataSource, "replica-b"));
        replicaB.runNow();
        replicaA.runNow();
    }

    @AfterEach
    void stopReplicaB() {
        schedulerB.shutdown();
    }

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("alice").roles("PLATFORM_ADMIN").postProcessor();
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").roles("PLATFORM_SUPPORT").postProcessor();
    }

    private MvcResult submit(String body, RequestPostProcessor caller, String reason) throws Exception {
        var request = post("/admin/v1/replicas/controls/probe").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(caller);
        return mvc.perform(reason == null ? request : request.header(AdminInvocation.REASON_HEADER, reason))
                .andReturn();
    }

    private JsonNode command(String location) throws Exception {
        return JSON.readTree(mvc.perform(get(location).with(support())).andReturn().getResponse()
                .getContentAsString());
    }

    private int commands() {
        return new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM job_control_command", Integer.class);
    }

    @Test
    @DisplayName("a control reaches both replicas once each, and the command is SUCCEEDED with both results")
    void reachesEveryReplica() throws Exception {
        MvcResult submitted = submit("{\"label\":\"one\"}", admin(), null);

        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        String location = submitted.getResponse().getHeader("Location");
        assertThat(location).startsWith("/admin/v1/replicas/commands/");
        assertThat(command(location).path("operationEnvelope").path("status").asText()).isEqualTo("RUNNING");

        replicaA.runNow();
        replicaB.runNow();
        replicaA.runNow();
        replicaB.runNow();

        JsonNode done = command(location);
        assertThat(done.path("operationEnvelope").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("targets").findValuesAsText("owner")).containsExactly("replica-a", "replica-b");
        assertThat(done.path("targets").findValuesAsText("state")).containsOnly("SUCCEEDED");
        assertThat(done.path("targets").get(1).path("result").path("replica").asText()).isEqualTo("replica-b");
        assertThat(ON_A.applied).containsExactly("one");
        assertThat(ON_B.applied).containsExactly("one");
        assertThat(AUDIT).filteredOn(event -> "replica-command".equals(event.resource().type())).singleElement()
                .satisfies(event -> assertThat(event.attributes()).containsEntry("control", "probe")
                        .containsEntry("targets", List.of("replica-a", "replica-b")));
    }

    @Test
    @DisplayName("invalid arguments, a missing operation, a missing reason and an unknown control record nothing")
    void refusals() throws Exception {
        assertThat(submit("{\"label\":\"\"}", admin(), null).getResponse().getStatus()).isEqualTo(400);
        assertThat(submit("{\"label\":\"x\"}", support(), null).getResponse().getStatus()).isEqualTo(403);
        assertThat(submit("{\"label\":\"x\",\"privileged\":true}", admin(), null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(mvc.perform(post("/admin/v1/replicas/controls/nosuch").contentType(MediaType.APPLICATION_JSON)
                .content("{}").with(admin())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(commands()).isZero();
        assertThat(submit("{\"label\":\"x\",\"privileged\":true}", admin(), "INC-9").getResponse().getStatus())
                .isEqualTo(202);
    }

    @Test
    @DisplayName("a replica that never answers is named UNACKNOWLEDGED after the deadline, and the command FAILED")
    void silentReplicaIsNamed() throws Exception {
        String location = submit("{\"label\":\"two\"}", admin(), null).getResponse().getHeader("Location");
        replicaA.runNow();
        new JdbcTemplate(dataSource).update("UPDATE job_control_command SET deadline = now() - interval '1 second'");

        JsonNode failed = command(location);

        assertThat(failed.path("operationEnvelope").path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("operationEnvelope").path("failure").path("code").asText())
                .isEqualTo("ludwig.job.error.control-unacknowledged");
        assertThat(failed.path("operationEnvelope").path("failure").path("args").toString()).contains("replica-b");
        assertThat(failed.path("targets").findValuesAsText("state")).containsExactly("SUCCEEDED", "UNACKNOWLEDGED");
        replicaB.runNow();
        assertThat(ON_B.applied).isEmpty();
    }

    @Test
    @DisplayName("a handler that throws on one replica fails that entry and the command; the other still applies it")
    void failureOnOneReplica() throws Exception {
        ON_B.failing = true;
        String location = submit("{\"label\":\"three\"}", admin(), null).getResponse().getHeader("Location");
        replicaA.runNow();
        replicaB.runNow();

        JsonNode failed = command(location);

        assertThat(failed.path("operationEnvelope").path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("targets").get(0).path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(failed.path("targets").get(1).path("failureCode").asText())
                .isEqualTo("ludwig.job.error.control-failed");
        assertThat(ON_A.applied).containsExactly("three");
    }

    @Test
    @DisplayName("the live replicas are those seen within three poll intervals")
    void liveness() throws Exception {
        assertThat(JSON.readTree(mvc.perform(get("/admin/v1/replicas").with(support())).andReturn().getResponse()
                .getContentAsString()).findValuesAsText("owner")).containsExactly("replica-a", "replica-b");

        new JdbcTemplate(dataSource).update("UPDATE job_instance SET last_seen_at = now() - interval '4 hours'"
                + " WHERE owner = 'replica-b'");

        assertThat(JSON.readTree(mvc.perform(get("/admin/v1/replicas").with(support())).andReturn().getResponse()
                .getContentAsString()).findValuesAsText("owner")).containsExactly("replica-a");
        assertThat(submit("{\"label\":\"four\"}", admin(), null).getResponse().getContentAsString())
                .doesNotContain("replica-b");
    }
}
