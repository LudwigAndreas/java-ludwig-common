package ru.ludwigandreas.outbox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.integration.testmodel.TransactionalPublishHelper;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.repository.OutboxStatusHistoryRepository;
import ru.ludwigandreas.outbox.scheduler.OutboxOutcomeRecorder;
import ru.ludwigandreas.outbox.scheduler.OutboxRetentionPurge;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The retention purge against the real outbox tables, with the default periods and a small batch size so a backlog
 * spans several committed batches.
 */
@SpringBootTest(classes = OutboxTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.enabled=true",
            "ludwig.security.jwt.enabled=false",
            "ludwig.outbox.polling.enabled=false",
            "ludwig.outbox.retention.batch-size=3",
            "ludwig.admin.grants.jobs.read[0]=OPS_SUPPORT",
            "ludwig.admin.grants.outbox.read[0]=OPS_ADMIN",
            "ludwig.admin.grants.outbox.operate[0]=OPS_ADMIN"})
@Import({PostgresContainerConfiguration.class, OutboxRetentionPurgeIntegrationTest.CapturingAudit.class})
@AutoConfigureMockMvc
class OutboxRetentionPurgeIntegrationTest {

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class CapturingAudit {
        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionalPublishHelper publishHelper;

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private OutboxStatusHistoryRepository history;

    @Autowired
    private OutboxOutcomeRecorder outcomes;

    @Autowired
    private OutboxRetentionPurge purge;

    @Autowired
    private RunLock lock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        history.deleteAll();
        repository.deleteAll();
        AUDIT.clear();
    }

    /** A message in {@code status} that became terminal {@code age} ago, with one history row. */
    private UUID message(OutboxStatus status, Duration age) {
        OutboxMessage message = publishHelper.publish(OutboxEvent.builder().aggregateType("Order")
                .aggregateId(UUID.randomUUID().toString()).eventType("OrderCreated")
                .payload(Map.of("id", 1)).build()).orElseThrow();
        message.setStatus(status);
        message.setFinishedAt(age == null ? null : Instant.now().minus(age));
        repository.save(message);
        jdbc.update("insert into outbox_status_history (id, outbox_message_id, status, attempt_number, occurred_at)"
                + " values (?, ?, ?, 0, now())", UUID.randomUUID(), message.getId(), status.name());
        return message.getId();
    }

    private int historyOf(UUID id) {
        return jdbc.queryForObject("select count(*) from outbox_status_history where outbox_message_id = ?",
                Integer.class, id);
    }

    private List<AuditEvent> purges() {
        return AUDIT.stream().filter(event -> OutboxRetentionPurge.PURGED.equals(event.action())).toList();
    }

    private static RequestPostProcessor as(String role) {
        return TestPrincipalBuilder.user("alice").roles(role).postProcessor();
    }

    @Test
    @DisplayName("a published message eight days old is purged with its history; a dead letter ten days old is kept")
    void defaultsPurgePublishedAndKeepRecentDeadLetters() {
        UUID oldPublished = message(OutboxStatus.PUBLISHED, Duration.ofDays(8));
        UUID recentPublished = message(OutboxStatus.PUBLISHED, Duration.ofDays(6));
        UUID recentDeadLetter = message(OutboxStatus.DEAD_LETTER, Duration.ofDays(10));
        UUID oldDiscarded = message(OutboxStatus.DISCARDED, Duration.ofDays(31));

        purge.runNow();

        assertThat(repository.findById(oldPublished)).isEmpty();
        assertThat(historyOf(oldPublished)).isZero();
        assertThat(repository.findById(oldDiscarded)).isEmpty();
        assertThat(repository.findById(recentPublished)).isPresent();
        assertThat(repository.findById(recentDeadLetter)).isPresent();
        assertThat(historyOf(recentDeadLetter)).isOne();
    }

    @Test
    @DisplayName("a FAILED message is never purged, however old, and nor is one with no finished_at")
    void nonTerminalNeverPurged() {
        UUID failed = message(OutboxStatus.FAILED, Duration.ofDays(365));
        UUID pending = message(OutboxStatus.PENDING, Duration.ofDays(365));
        UUID deadLetterWithoutFinish = message(OutboxStatus.DEAD_LETTER, null);

        purge.runNow();

        assertThat(repository.findById(failed)).isPresent();
        assertThat(repository.findById(pending)).isPresent();
        assertThat(repository.findById(deadLetterWithoutFinish)).isPresent();
        assertThat(purges()).isEmpty();
    }

    @Test
    @DisplayName("three expired dead letters are one batch and one audit event naming the status and the count")
    void oneAuditEventPerBatch() {
        for (int i = 0; i < 3; i++) {
            message(OutboxStatus.DEAD_LETTER, Duration.ofDays(40));
        }

        purge.runNow();

        assertThat(purges()).hasSize(1);
        AuditEvent event = purges().get(0);
        assertThat(event.category()).isEqualTo("outbox");
        assertThat(event.attributes()).containsEntry("status", "DEAD_LETTER").containsEntry("count", 3);
    }

    @Test
    @DisplayName("a backlog larger than a batch is deleted in committed batches, each audited, PARTIAL until clear")
    void backlogInBatches() {
        for (int i = 0; i < 7; i++) {
            message(OutboxStatus.PUBLISHED, Duration.ofDays(9));
        }

        purge.runNow();

        assertThat(repository.count()).isZero();
        assertThat(purges()).extracting(event -> event.attributes().get("count")).containsExactly(3, 3, 1);
        assertThat(purges()).extracting(event -> event.outcome().status().name())
                .containsExactly("PARTIAL", "PARTIAL", "SUCCESS");
    }

    @Test
    @DisplayName("while another replica holds the purge lease, this one deletes nothing")
    void onlyTheLeaseHolderPurges() {
        UUID old = message(OutboxStatus.PUBLISHED, Duration.ofDays(9));

        Optional<RunLockHandle> elsewhere = lock.tryAcquire(OutboxRetentionPurge.JOB_NAME, Duration.ofMinutes(5));
        assertThat(elsewhere).isPresent();
        try (RunLockHandle held = elsewhere.get()) {
            purge.runNow();
            assertThat(repository.findById(old)).isPresent();
        }

        purge.runNow();
        assertThat(repository.findById(old)).isEmpty();
    }

    @Test
    @DisplayName("a dead letter retried on its twenty-ninth day leaves retention, and dead-lettering again restarts it")
    void retriedDeadLetterRestartsItsRetention() throws Exception {
        UUID id = message(OutboxStatus.DEAD_LETTER, Duration.ofDays(29));

        int retried = mvc.perform(post("/admin/v1/outbox/messages/" + id + "/retry").with(as("OPS_ADMIN")))
                .andReturn().getResponse().getStatus();
        assertThat(retried).isEqualTo(200);
        assertThat(repository.findById(id).orElseThrow().getFinishedAt()).isNull();

        jdbc.update("update outbox_message set status = 'PROCESSING' where id = ?", id);
        outcomes.recordFailure(id, "still failing", false);
        OutboxMessage again = repository.findById(id).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(again.getFinishedAt()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));

        purge.runNow();
        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("the purge is a registered job, listed by the jobs area")
    void listedAsAJob() throws Exception {
        String body = mvc.perform(get("/admin/v1/jobs").with(as("OPS_SUPPORT")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(OutboxRetentionPurge.JOB_NAME);
    }
}
