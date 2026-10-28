package ru.ludwigandreas.job.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code jobs} area through MVC dispatch, the administrative guard and the real lease table.
 */
@SpringBootTest(classes = JobAdminIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.jwt.enabled=false",
            "ludwig.web.i18n.configure-validator=false",
            // The fixture has no application changelog of its own; job-core's is the schema under test.
            "spring.liquibase.change-log=classpath:db/changelog/job-core/job-core-changelog.xml",
            "ludwig.admin.grants.jobs.read[0]=PLATFORM_SUPPORT",
            "ludwig.admin.grants.jobs.read[1]=PLATFORM_ADMIN",
            "ludwig.admin.grants.jobs.operate[0]=PLATFORM_ADMIN",
            "ludwig.admin.grants.jobs.privileged[0]=PLATFORM_ADMIN"})
@AutoConfigureMockMvc
class JobAdminIT {

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    /** A job that serializes on a run lease of its own name, as most platform jobs do. */
    static final class SweepJob extends SelfSchedulingLifecycle {

        SweepJob(TaskScheduler scheduler) {
            super("sweep", scheduler, ScheduleSpec.fixedDelay(Duration.ofHours(1), Duration.ofHours(1)), Duration.ZERO);
        }

        @Override
        protected void runOnce() {
            // The work is irrelevant here; what is under test is who may start it and when.
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
        ThreadPoolTaskScheduler jobScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.initialize();
            return scheduler;
        }

        @Bean
        SweepJob sweepJob(ThreadPoolTaskScheduler jobScheduler) {
            return new SweepJob(jobScheduler);
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

    @BeforeEach
    void reset() throws Exception {
        AUDIT.clear();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM job_run_lock");
        }
    }

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("alice").roles("PLATFORM_ADMIN").postProcessor();
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").roles("PLATFORM_SUPPORT").postProcessor();
    }

    private RunLockHandle holdLease(String owner) {
        return new JdbcRunLock(dataSource, owner).tryAcquire("sweep", Duration.ofMinutes(5)).orElseThrow();
    }

    @Test
    @DisplayName("a job is listed with the lease the table records, the same answer from any replica")
    void listsJobsWithTheirLease() throws Exception {
        holdLease("pod-7");

        String body = mvc.perform(get("/admin/v1/jobs").with(support())).andReturn().getResponse()
                .getContentAsString();

        assertThat(body).contains("\"name\":\"sweep\"", "\"owner\":\"pod-7\"", "\"held\":true");
    }

    @Test
    @DisplayName("run now while another replica holds the lease is a 409 naming the holder, and starts nothing")
    void runNowWhileHeldElsewhere() throws Exception {
        holdLease("pod-7");

        MvcResult result = mvc.perform(post("/admin/v1/jobs/sweep/run").with(admin())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("pod-7");
        assertThat(AUDIT).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("jobs.operate");
            assertThat(event.outcome().status().name()).isEqualTo("DENIED");
        });
    }

    @Test
    @DisplayName("run now with the lease free is a 202 and is audited; support may look but not run")
    void runNow() throws Exception {
        MvcResult accepted = mvc.perform(post("/admin/v1/jobs/sweep/run").with(admin())).andReturn();
        MvcResult refused = mvc.perform(post("/admin/v1/jobs/sweep/run").with(support())).andReturn();
        MvcResult unknown = mvc.perform(post("/admin/v1/jobs/nope/run").with(admin())).andReturn();

        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(AUDIT).filteredOn(event -> "admin".equals(event.category()))
                .extracting(event -> event.resource().id()).containsExactly("sweep");
    }

    @Test
    @DisplayName("forced release needs a reason, frees the lease, and records the former owner")
    void forcedRelease() throws Exception {
        holdLease("pod-7");

        MvcResult noReason = mvc.perform(post("/admin/v1/jobs/locks/sweep/release").with(admin())).andReturn();
        MvcResult released = mvc.perform(post("/admin/v1/jobs/locks/sweep/release")
                .header(AdminInvocation.REASON_HEADER, "pod-7 was evicted").with(admin())).andReturn();
        MvcResult again = mvc.perform(post("/admin/v1/jobs/locks/sweep/release")
                .header(AdminInvocation.REASON_HEADER, "again").with(admin())).andReturn();

        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(released.getResponse().getStatus()).isEqualTo(200);
        assertThat(released.getResponse().getContentAsString()).contains("\"owner\":\"pod-7\"");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        AuditEvent event = AUDIT.stream().filter(audit -> "admin".equals(audit.category())).findFirst().orElseThrow();
        assertThat(event.attributes()).containsEntry("formerOwner", "pod-7")
                .containsEntry("reason", "pod-7 was evicted");
        assertThat(new JdbcRunLock(dataSource, "pod-8").tryAcquire("sweep", Duration.ofMinutes(5))).isPresent();
    }
}
