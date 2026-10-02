package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.DeferredSubmissionWorker;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.engine.SubmissionStore;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.storage.api.ObjectStore;

/**
 * A deferred submission is accepted without being processed, claimed under a lease, and processed by a worker.
 *
 * <h2>What the lease buys, and what this test checks about it</h2>
 *
 * <p>The submission row is the queue. A claim writes a holder and an expiry, so an instance that stops renewing
 * loses the submission to the next tick of another instance - which is how a pod dying mid-apply recovers with
 * nobody involved. The cases below check the three things that have to be true for that: a claim is exclusive while
 * it holds, a lapsed claim is reclaimable, and a claim is released when the submission reaches a terminal state.
 *
 * <p>The last one is the least obvious and the most important. A terminal submission that kept its lease would be
 * invisible to the claim query for the lease's duration and then claimed <em>again</em>, which is how a failed apply
 * becomes a repeated one.
 */
@SpringBootTest(classes = FileActionTestApplication.class,
        properties = {
            "ludwig.file-action.storage.uploads=file:///tmp/ludwig-file-action-it/uploads",
            "ludwig.file-action.storage.artifacts=file:///tmp/ludwig-file-action-it/artifacts",
            "ludwig.file-action.scanning.mode=DISABLED",
            "ludwig.file-action.actions.order-import.commit-policy=PER_ROW",
            "ludwig.file-action.actions.order-import.mode=DIRECT",
            "ludwig.file-action.actions.order-import.execution=DEFERRED",
            "ludwig.file-action.actions.order-import.max-rows=20000",
            // The scheduled worker is pushed far out so that this test drives pollOnce by hand: a background tick
            // racing the assertions would make every case here intermittent.
            "ludwig.file-action.deferred.initial-delay=PT1H",
            "ludwig.file-action.storage.retention-interval=PT1H"
        })
class DeferredClaimIT extends PostgresBackedTest {

    @Autowired
    private FileActionService service;

    @Autowired
    private RecordingOrderHandler handler;

    @Autowired
    private FileActionSubmissionRepository submissions;

    @Autowired
    private SubmissionStore store;

    @Autowired
    private FileActionRegistry registry;

    @Autowired
    private ObjectStore objectStore;

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
    @DisplayName("a DEFERRED submit stores and accepts without reading the file")
    void deferredSubmitDoesNotProcess() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(5));

        assertThat(submission.state())
                .as("the request answers 202 and the worker does the work; reading here would hold the"
                        + " connection for the length of the read")
                .isEqualTo(FileActionState.UPLOADED);
        assertThat(submission.rowsRead()).isZero();
        assertThat(handler.applied()).isEmpty();
    }

    @Test
    @DisplayName("a worker tick claims the submission and applies it")
    void aWorkerTickApplies() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(5));

        worker("instance-a").pollOnce();

        assertThat(submissions.findById(submission.id()).orElseThrow().getState())
                .isEqualTo(FileActionState.APPLIED);
        assertThat(handler.applied()).extracting(OrderLineRow::sku)
                .containsExactly("A-1", "A-2", "A-3", "A-4", "A-5");
    }

    @Test
    @DisplayName("a claim is exclusive while it holds, so two instances do not process one submission")
    void aClaimIsExclusive() throws IOException {
        submit(Fixtures.orders(4));

        List<?> first = submissions.claimForProcessing(FileActionState.UPLOADED, "instance-a",
                clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), 10);
        List<?> second = submissions.claimForProcessing(FileActionState.UPLOADED, "instance-b",
                clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), 10);

        assertThat(first).hasSize(1);
        assertThat(second)
                .as("without exclusivity both instances read the file and the orders are created twice")
                .isEmpty();
    }

    @Test
    @DisplayName("a lapsed claim is reclaimable, which is how a dead instance's submission recovers")
    void aLapsedClaimIsReclaimable() throws IOException {
        submit(Fixtures.orders(4));
        // Claimed with a lease that has already expired, which is what a pod that died mid-apply leaves behind.
        submissions.claimForProcessing(FileActionState.UPLOADED, "dead-instance", clock.instant(),
                clock.instant().minus(Duration.ofMinutes(1)), 10);

        List<?> reclaimed = submissions.claimForProcessing(FileActionState.UPLOADED, "instance-b",
                clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), 10);

        assertThat(reclaimed)
                .as("a submission whose holder is gone must come back without anybody intervening")
                .hasSize(1);
    }

    @Test
    @DisplayName("a terminal submission releases its lease, so it is never claimed a second time")
    void aTerminalSubmissionReleasesItsLease() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));
        worker("instance-a").pollOnce();

        assertThat(submissions.findById(submission.id()).orElseThrow().getLockedBy())
                .as("a terminal submission that kept its lease would be invisible to the claim query for the"
                        + " lease's duration and then claimed again - a failed apply becoming a repeated one")
                .isNull();
        assertThat(submissions.findById(submission.id()).orElseThrow().getLeaseExpiresAt()).isNull();
    }

    @Test
    @DisplayName("a second tick finds nothing, because the first one finished the work")
    void aSecondTickFindsNothing() throws IOException {
        submit(Fixtures.orders(3));
        worker("instance-a").pollOnce();
        handler.reset();

        worker("instance-a").pollOnce();

        assertThat(handler.applied()).isEmpty();
    }

    @Test
    @DisplayName("a submission past its attempt cap is left alone rather than retried for ever")
    void theAttemptCapIsHonoured() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));
        for (int attempt = 0; attempt < 3; attempt++) {
            store.recordAttempt(submission.id());
        }

        worker("instance-a").pollOnce();

        assertThat(submissions.findById(submission.id()).orElseThrow().getState())
                .as("a submission failing the same way a fourth time is failing for a reason a retry will not"
                        + " change, and a worker that kept trying would hide it behind a log line per tick")
                .isEqualTo(FileActionState.REJECTED);
        assertThat(handler.applied()).isEmpty();
    }

    @Test
    @DisplayName("a row the handler refuses is one reject and the rest are applied")
    void aHandlerRejectIsIsolated() throws IOException {
        handler.refuse("A-3");
        SubmissionSnapshot submission = submit(Fixtures.orders(20));

        worker("instance-a").pollOnce();

        assertThat(handler.applied()).hasSize(19);
        assertThat(submissions.findById(submission.id()).orElseThrow().getRowsRejected()).isEqualTo(1);
        assertThat(submissions.findById(submission.id()).orElseThrow().getState())
                .as("a submission that applied nineteen of twenty rows is APPLIED with a reject count; from the"
                        + " caller's point of view the action happened")
                .isEqualTo(FileActionState.APPLIED);
    }

    @Test
    @DisplayName("the reject is stored with the row number the user can navigate to")
    void theRejectIsAddressed() throws IOException {
        handler.refuse("A-3");
        SubmissionSnapshot submission = submit(Fixtures.orders(20));

        worker("instance-a").pollOnce();

        List<Integer> rows = jdbc.queryForList(
                "select displayed_row from file_action_row_reject where submission_id = ?",
                Integer.class, submission.id());

        assertThat(rows)
                .as("row 3 of the data is displayed row 4, because row 1 is the header - and the off-by-one"
                        + " between those two is the commonest complaint about an import error report")
                .containsExactly(4);
    }

    private DeferredSubmissionWorker worker(String owner) {
        return new DeferredSubmissionWorker(registry, submissions, store, service, objectStore, owner,
                Duration.ofMinutes(5), 5, 3, clock);
    }

    private SubmissionSnapshot submit(byte[] content) {
        return service.submit("order-import", () -> new ByteArrayInputStream(content),
                "orders-" + UUID.randomUUID() + ".xlsx", SourceFormat.XLSX.mediaType());
    }
}
