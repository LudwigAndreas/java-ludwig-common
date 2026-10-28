package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.NestedTestConfiguration;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.audit.FileActionAuditActions;
import ru.ludwigandreas.fileaction.engine.DeferredSubmissionWorker;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.engine.SubmissionStore;
import ru.ludwigandreas.fileaction.exception.SubmissionStateException;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * A cancelled submission ends {@code CANCELLED}, on every path that can stop: before the deferred worker applies it,
 * while it waits for a confirmation, and between two batches of a running apply.
 *
 * <p>Until this change a stopped pass was recorded {@code APPLIED} with a successful {@code file-action.applied}
 * event, which told the caller, the audit trail and support that an action happened that the caller had stopped.
 */
class FileActionCancelIT {

    private static final String UPLOADS =
            "ludwig.file-action.storage.uploads=file:///tmp/ludwig-file-action-it/uploads";
    private static final String ARTIFACTS =
            "ludwig.file-action.storage.artifacts=file:///tmp/ludwig-file-action-it/artifacts";

    /** Every audit event the context records, so the stop and the absence of an apply can be asserted. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingAudit {

        @Bean
        RecordingSink recordingSink() {
            return new RecordingSink();
        }
    }

    static final class RecordingSink implements AuditSink {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        List<String> actionsFor(UUID submission) {
            return events.stream()
                    .filter(event -> event.resource() != null && submission.toString().equals(event.resource().id()))
                    .map(AuditEvent::action)
                    .toList();
        }

        void clear() {
            events.clear();
        }
    }

    abstract static class Support extends PostgresBackedTest {

        @Autowired
        FileActionService service;

        @Autowired
        RecordingOrderHandler handler;

        @Autowired
        FileActionSubmissionRepository submissions;

        @Autowired
        SubmissionStore store;

        @Autowired
        FileActionRegistry registry;

        @Autowired
        ObjectStore objectStore;

        @Autowired
        Clock clock;

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        RecordingSink audit;

        @BeforeEach
        void reset() {
            handler.reset();
            audit.clear();
            jdbc.update("delete from file_action_row_reject");
            jdbc.update("delete from file_action_submission");
        }

        SubmissionSnapshot submit(int rows) throws Exception {
            byte[] content = Fixtures.orders(rows);
            return service.submit("order-import", () -> new ByteArrayInputStream(content),
                    "orders-" + UUID.randomUUID() + ".xlsx", SourceFormat.XLSX.mediaType());
        }

        DeferredSubmissionWorker worker() {
            return new DeferredSubmissionWorker(registry, submissions, store, service, objectStore, "instance-a",
                    Duration.ofMinutes(5), 5, 3, clock);
        }

        SubmissionSnapshot reloaded(UUID id) {
            return SubmissionSnapshot.of(submissions.findById(id).orElseThrow());
        }
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = {FileActionTestApplication.class, RecordingAudit.class}, properties = {
        UPLOADS, ARTIFACTS,
        "ludwig.file-action.scanning.mode=DISABLED",
        "ludwig.file-action.actions.order-import.commit-policy=ALL_OR_NOTHING",
        "ludwig.file-action.actions.order-import.mode=DIRECT",
        "ludwig.file-action.actions.order-import.execution=DEFERRED",
        "ludwig.file-action.deferred.initial-delay=PT1H",
        "ludwig.file-action.storage.retention-interval=PT1H"})
    @DisplayName("deferred")
    class Deferred extends Support {

        @Test
        @DisplayName("cancelled before the worker claims it: no row reaches the handler, and it ends CANCELLED")
        void cancelledBeforeClaim() throws Exception {
            SubmissionSnapshot submitted = submit(3);
            assertThat(submitted.state()).isEqualTo(FileActionState.UPLOADED);

            SubmissionSnapshot requested = service.cancel("order-import", submitted.id());
            assertThat(requested.state()).isEqualTo(FileActionState.UPLOADED);

            worker().pollOnce();

            SubmissionSnapshot ended = reloaded(submitted.id());
            assertThat(handler.applied()).isEmpty();
            assertThat(ended.state()).isEqualTo(FileActionState.CANCELLED);
            assertThat(ended.state().status()).isEqualTo(OperationStatus.CANCELLED);
            assertThat(ended.rowsApplied()).isZero();
            assertThat(ended.failureCode()).isNull();
            assertThat(audit.actionsFor(submitted.id()))
                    .contains(FileActionAuditActions.CANCELLATION_REQUESTED, FileActionAuditActions.CANCELLED)
                    .doesNotContain(FileActionAuditActions.APPLIED);
        }

        @Test
        @DisplayName("a cancelled submission cancelled again answers its terminal state and records nothing more")
        void cancelledAgain() throws Exception {
            SubmissionSnapshot submitted = submit(3);
            service.cancel("order-import", submitted.id());
            worker().pollOnce();
            int recorded = audit.actionsFor(submitted.id()).size();

            SubmissionSnapshot again = service.cancel("order-import", submitted.id());

            assertThat(again.state()).isEqualTo(FileActionState.CANCELLED);
            assertThat(audit.actionsFor(submitted.id())).hasSize(recorded);
        }
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = {FileActionTestApplication.class, RecordingAudit.class}, properties = {
        UPLOADS, ARTIFACTS,
        "ludwig.file-action.scanning.mode=DISABLED",
        "ludwig.file-action.actions.order-import.commit-policy=PER_ROW",
        "ludwig.file-action.actions.order-import.mode=CONFIRM",
        "ludwig.file-action.actions.order-import.execution=INLINE",
        "ludwig.file-action.actions.order-import.confirm-ttl=PT30M",
        "ludwig.file-action.deferred.initial-delay=PT1H",
        "ludwig.file-action.storage.retention-interval=PT1H"})
    @DisplayName("awaiting confirmation")
    class AwaitingConfirmation extends Support {

        @Test
        @DisplayName("cancelling a VALIDATED submission ends it CANCELLED at once, and a later confirm is refused")
        void validatedEndsAtOnce() throws Exception {
            SubmissionSnapshot validated = submit(3);
            assertThat(validated.state()).isEqualTo(FileActionState.VALIDATED);

            SubmissionSnapshot cancelled = service.cancel("order-import", validated.id());

            assertThat(cancelled.state()).isEqualTo(FileActionState.CANCELLED);
            assertThat(cancelled.finishedAt()).isNotNull();
            assertThat(audit.actionsFor(validated.id())).contains(FileActionAuditActions.CANCELLED);
            assertThatThrownBy(() -> service.confirm("order-import", validated.id()))
                    .isInstanceOf(SubmissionStateException.class);
            assertThat(handler.applied()).isEmpty();
            assertThat(reloaded(validated.id()).state()).isEqualTo(FileActionState.CANCELLED);
        }

        @Test
        @DisplayName("an administrator's cancel of a VALIDATED submission ends it the same way")
        void administratorCancelsValidated() throws Exception {
            SubmissionSnapshot validated = submit(3);

            SubmissionSnapshot cancelled = service.cancelByAdministrator(validated.id());

            assertThat(cancelled.state()).isEqualTo(FileActionState.CANCELLED);
        }
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = {FileActionTestApplication.class, RecordingAudit.class}, properties = {
        UPLOADS, ARTIFACTS,
        "ludwig.file-action.scanning.mode=DISABLED",
        "ludwig.file-action.actions.order-import.commit-policy=PER_BATCH",
        "ludwig.file-action.actions.order-import.batch-size=2",
        "ludwig.file-action.actions.order-import.mode=DIRECT",
        "ludwig.file-action.actions.order-import.execution=DEFERRED",
        "ludwig.file-action.deferred.initial-delay=PT1H",
        "ludwig.file-action.storage.retention-interval=PT1H"})
    @DisplayName("between batches")
    class BetweenBatches extends Support {

        @Test
        @DisplayName("cancelled while the first batch is open: that batch commits, no later batch runs, CANCELLED")
        void stopsAtTheNextBatch() throws Exception {
            SubmissionSnapshot submitted = submit(6);
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // A-2 is the last row of the first batch of two: the handler stops inside that open batch.
            handler.pauseAfter("A-2", reached, release);

            CompletableFuture<Void> running = CompletableFuture.runAsync(() -> worker().pollOnce());
            assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
            service.cancel("order-import", submitted.id());
            release.countDown();
            running.get(30, TimeUnit.SECONDS);

            SubmissionSnapshot ended = reloaded(submitted.id());
            assertThat(ended.state()).isEqualTo(FileActionState.CANCELLED);
            assertThat(ended.rowsApplied()).isEqualTo(2);
            assertThat(handler.applied()).extracting(OrderLineRow::sku).containsExactly("A-1", "A-2");
            assertThat(audit.actionsFor(submitted.id()))
                    .contains(FileActionAuditActions.CANCELLED)
                    .doesNotContain(FileActionAuditActions.APPLIED);
        }
    }
}
