package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.RetentionJob;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.engine.SubmissionStore;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.storage.api.ObjectStore;

/**
 * Retention removes a submission's stored bytes, and the envelope stops claiming a result that is gone.
 *
 * <h2>Why the module does this rather than a bucket lifecycle rule</h2>
 *
 * <p>A lifecycle rule would delete the object and leave the submission row claiming a result that is no longer
 * there - so a poll would answer {@code SUCCEEDED} with a link to nothing. That is exactly the outcome
 * {@code OperationStatus.EXPIRED} exists to prevent, and the cases below are what make the envelope honest: the
 * objects go, the state becomes {@code EXPIRED}, and the result link goes with them.
 */
@SpringBootTest(classes = FileActionTestApplication.class,
        properties = {
            "ludwig.file-action.storage.uploads=file:///tmp/ludwig-file-action-it/uploads",
            "ludwig.file-action.storage.artifacts=file:///tmp/ludwig-file-action-it/artifacts",
            "ludwig.file-action.scanning.mode=DISABLED",
            "ludwig.file-action.actions.order-import.commit-policy=PER_ROW",
            "ludwig.file-action.actions.order-import.mode=CONFIRM",
            "ludwig.file-action.actions.order-import.execution=INLINE",
            // Both scheduled jobs pushed out, so this test drives the retention pass by hand rather than racing it.
            "ludwig.file-action.deferred.initial-delay=PT1H",
            "ludwig.file-action.storage.retention-interval=PT1H"
        })
class RetentionIT extends PostgresBackedTest {

    @Autowired
    private FileActionService service;

    @Autowired
    private RecordingOrderHandler handler;

    @Autowired
    private FileActionSubmissionRepository submissions;

    @Autowired
    private SubmissionStore store;

    @Autowired
    private ObjectStore objectStore;

    @Autowired
    private RunLock runLock;

    @Autowired
    private AuditSink audit;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        handler.reset();
        jdbc.update("delete from file_action_row_reject");
        jdbc.update("delete from file_action_submission");
    }

    @Test
    @DisplayName("a submission whose window has closed has its upload and artifacts deleted")
    void expiredArtifactsAreDeleted() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));
        FileActionSubmissionEntity row = submissions.findById(submission.id()).orElseThrow();
        String uploadUri = row.getObjectUri();
        String boundUri = row.getBoundRowsUri();
        assertThat(objectStore.exists(uploadUri)).isTrue();
        assertThat(objectStore.exists(boundUri)).isTrue();

        expire(submission.id());
        retention().pollOnce();

        assertThat(objectStore.exists(uploadUri)).isFalse();
        assertThat(objectStore.exists(boundUri)).isFalse();
    }

    @Test
    @DisplayName("the submission becomes EXPIRED, which is terminal and not a failure")
    void theSubmissionBecomesExpired() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));

        expire(submission.id());
        retention().pollOnce();

        SubmissionSnapshot after = service.find("order-import", submission.id());
        assertThat(after.state()).isEqualTo(FileActionState.EXPIRED);
        assertThat(after.state().status().isFailure())
                .as("retention working as designed must not page anybody")
                .isFalse();
        assertThat(after.state().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("the artifact references are cleared, so nothing links to bytes that are gone")
    void artifactReferencesAreCleared() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));

        expire(submission.id());
        retention().pollOnce();

        FileActionSubmissionEntity row = submissions.findById(submission.id()).orElseThrow();
        assertThat(row.getBoundRowsUri()).isNull();
        assertThat(row.getErrorReportUri()).isNull();
    }

    @Test
    @DisplayName("a submission whose window has not closed is left alone")
    void unexpiredSubmissionsAreUntouched() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));

        retention().pollOnce();

        FileActionSubmissionEntity row = submissions.findById(submission.id()).orElseThrow();
        assertThat(row.getState()).isEqualTo(FileActionState.VALIDATED);
        assertThat(objectStore.exists(row.getObjectUri())).isTrue();
    }

    @Test
    @DisplayName("a second pass does not revisit a submission it has already collected")
    void aSecondPassFindsNothing() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));
        expire(submission.id());
        retention().pollOnce();

        retention().pollOnce();

        assertThat(submissions.findById(submission.id()).orElseThrow().getState())
                .isEqualTo(FileActionState.EXPIRED);
    }

    @Test
    @DisplayName("the pass holds the platform's one distributed lock under the module's name")
    void thePassHoldsTheRunLock() {
        // Closed, in a try-with-resources. The first version of this case acquired and never released, and the
        // thirty-second lease it left behind made every other case in this class fail - because runIfAvailable
        // correctly did nothing. A test that leaks a distributed lock is indistinguishable from the feature being
        // broken, which is how long it took to spot.
        try (var handle = runLock.tryAcquire(RetentionJob.LOCK_NAME, Duration.ofSeconds(2)).orElseThrow()) {
            assertThat(handle.lockName())
                    .as("the lock name is part of the contract: an operator looking for what is holding up"
                            + " retention looks for this name")
                    .isEqualTo("file-action-retention");
        } catch (Exception closing) {
            throw new IllegalStateException("the lock handle could not be released", closing);
        }
        assertThat(RetentionJob.LOCK_NAME).isEqualTo("file-action-retention");
    }

    private RetentionJob retention() {
        return new RetentionJob(submissions, store, objectStore, runLock, audit, 100,
                Duration.ofSeconds(30), clock);
    }

    /** Brings a submission's expiry forward so a retention pass finds it. */
    private void expire(UUID id) {
        jdbc.update("update file_action_submission set expires_at = ? where id = ?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))), id);
    }

    private SubmissionSnapshot submit(byte[] content) {
        return service.submit("order-import", () -> new ByteArrayInputStream(content),
                "orders-" + UUID.randomUUID() + ".xlsx", SourceFormat.XLSX.mediaType());
    }
}
