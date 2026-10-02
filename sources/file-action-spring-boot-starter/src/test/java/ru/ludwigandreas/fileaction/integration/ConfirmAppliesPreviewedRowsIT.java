package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.exception.SubmissionStateException;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.PutOptions;

/**
 * A confirmation applies the rows the user was shown, not a re-read of the file.
 *
 * <h2>Why this test exists and what it would catch</h2>
 *
 * <p>This is the single most important guarantee of {@code CONFIRM} mode, and the one easiest to lose. The obvious
 * implementation of a confirm endpoint re-reads the stored object and re-binds it - it is simpler, it needs no
 * artifact, and it passes every test in which nothing changes between the two requests. What it does is apply
 * something other than the preview whenever anything <em>has</em> changed, silently, in the case that is hardest to
 * reproduce.
 *
 * <p>So the test changes something. After validation it <strong>replaces the stored object's bytes</strong> with a
 * different workbook, and then confirms. A re-reading implementation applies the replacement; this one applies what
 * the user approved. Nothing subtler would do: a test that only checked the row count would pass for both.
 */
@SpringBootTest(classes = FileActionTestApplication.class,
        properties = {
            "ludwig.file-action.storage.uploads=file:///tmp/ludwig-file-action-it/uploads",
            "ludwig.file-action.storage.artifacts=file:///tmp/ludwig-file-action-it/artifacts",
            "ludwig.file-action.scanning.mode=DISABLED",
            "ludwig.file-action.actions.order-import.commit-policy=PER_ROW",
            "ludwig.file-action.actions.order-import.mode=CONFIRM",
            "ludwig.file-action.actions.order-import.execution=INLINE",
            "ludwig.file-action.actions.order-import.confirm-ttl=PT30M",
            // Off, so a worker tick cannot pick up a submission this test is driving by hand.
            "ludwig.file-action.deferred.initial-delay=PT1H",
            "ludwig.file-action.storage.retention-interval=PT1H"
        })
class ConfirmAppliesPreviewedRowsIT extends PostgresBackedTest {

    @Autowired
    private FileActionService service;

    @Autowired
    private RecordingOrderHandler handler;

    @Autowired
    private FileActionSubmissionRepository submissions;

    @Autowired
    private ObjectStore objectStore;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        handler.reset();
        // The submission table is cleaned between cases, not only the handler. Every case here submits the same
        // fixture bytes, and the dedup claim is on the content hash - so without this, the second case is answered
        // with the first case's submission and asserts against a state it never created. That is exactly the
        // deduplication working, which is why the test has to clear the table rather than the module being changed.
        jdbc.update("delete from file_action_row_reject");
        jdbc.update("delete from file_action_submission");
    }

    @Test
    @DisplayName("validation applies nothing and leaves the submission waiting for a person")
    void validationAppliesNothing() throws IOException {
        SubmissionSnapshot submission = submit(Fixtures.orders(3));

        assertThat(submission.state()).isEqualTo(FileActionState.VALIDATED);
        assertThat(submission.rowsRead()).isEqualTo(3);
        assertThat(submission.rowsApplied()).isZero();
        assertThat(handler.applied())
                .as("a CONFIRM action that applied during validation would have no confirm step at all")
                .isEmpty();
    }

    @Test
    @DisplayName("confirmation applies the rows the preview reported")
    void confirmationApplies() throws IOException {
        SubmissionSnapshot validated = submit(Fixtures.orders(3));

        SubmissionSnapshot applied = service.confirm("order-import", validated.id());

        assertThat(applied.state()).isEqualTo(FileActionState.APPLIED);
        assertThat(applied.rowsApplied()).isEqualTo(3);
        assertThat(handler.applied()).extracting(OrderLineRow::sku).containsExactly("A-1", "A-2", "A-3");
    }

    @Test
    @DisplayName("the stored object changing between validate and confirm does not change what is applied")
    void confirmationIgnoresTheStoredObject() throws IOException {
        SubmissionSnapshot validated = submit(Fixtures.orders(3));
        String objectUri = objectUriOf(validated.id());

        // The file the user submitted, replaced. A confirm that re-read and re-bound would now apply B-1 and B-2;
        // this one applies the three rows the preview reported.
        replace(objectUri, Fixtures.orders(List.of(
                new Object[] {"B-1", 99, null}, new Object[] {"B-2", 98, null})));

        service.confirm("order-import", validated.id());

        assertThat(handler.applied())
                .as("a re-reading confirm would apply B-1 and B-2 - something other than what the user approved,"
                        + " silently, and only when something changed in between")
                .extracting(OrderLineRow::sku)
                .containsExactly("A-1", "A-2", "A-3");
    }

    @Test
    @DisplayName("a second confirmation of the same submission is refused")
    void confirmingTwiceIsRefused() throws IOException {
        SubmissionSnapshot validated = submit(Fixtures.orders(2));
        service.confirm("order-import", validated.id());
        handler.reset();

        assertThatThrownBy(() -> service.confirm("order-import", validated.id()))
                .as("without this, a double-clicked confirm button applies the file twice")
                .isInstanceOf(SubmissionStateException.class);
        assertThat(handler.applied()).isEmpty();
    }

    @Test
    @DisplayName("a row that fails its constraints is rejected and the rest are applied")
    void aConstraintViolationIsOneReject() throws IOException {
        // Twenty rows with one bad one, not three with one bad one: a third of the rows failing exceeds the
        // default reject-threshold of 0.1 and the whole submission is correctly refused, which is a different
        // behaviour from the one under test here. The first version of this case asserted on three rows and found
        // the threshold instead.
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(new Object[] {"A-" + i, i == 7 ? 0 : i, null});
        }
        SubmissionSnapshot validated = submit(Fixtures.orders(rows));

        assertThat(validated.state()).isEqualTo(FileActionState.VALIDATED);
        assertThat(validated.rowsRejected())
                .as("quantity 0 fails @Positive on the row record, during validation rather than during apply")
                .isEqualTo(1);

        service.confirm("order-import", validated.id());

        assertThat(handler.applied())
                .extracting(OrderLineRow::sku)
                .hasSize(19)
                .doesNotContain("A-7");
    }

    @Test
    @DisplayName("the bound-row artifact is stored, which is what confirmation reads")
    void theArtifactIsStored() throws IOException {
        SubmissionSnapshot validated = submit(Fixtures.orders(2));

        String uri = submissions.findById(validated.id()).orElseThrow().getBoundRowsUri();

        assertThat(uri).isNotNull();
        assertThat(objectStore.exists(uri)).isTrue();
    }

    @Test
    @DisplayName("a confirm window is set, so an unconfirmed submission does not wait for ever")
    void aConfirmWindowIsSet() throws IOException {
        SubmissionSnapshot validated = submit(Fixtures.orders(2));

        assertThat(validated.expiresAt()).isNotNull();
    }

    @Test
    @DisplayName("re-submitting the same file returns the first submission rather than validating it again")
    void resubmittingIsDeduplicated() throws IOException {
        byte[] content = Fixtures.orders(2);
        SubmissionSnapshot first = submit(content);

        SubmissionSnapshot second = submit(content);

        assertThat(second.id())
                .as("a user double-clicking the drop zone is the normal case, not an edge case")
                .isEqualTo(first.id());
    }

    /**
     * The stored object's URI.
     *
     * <p>{@code findById}, not {@code getReferenceById}: the latter hands back an uninitialised proxy and this test
     * holds no transaction, so touching a field on it throws. The same trap the service hit.
     */
    private String objectUriOf(UUID id) {
        return submissions.findById(id).orElseThrow().getObjectUri();
    }

    private SubmissionSnapshot submit(byte[] content) {
        return service.submit("order-import", () -> new ByteArrayInputStream(content),
                "orders-" + UUID.randomUUID() + ".xlsx", SourceFormat.XLSX.mediaType());
    }

    private void replace(String objectUri, byte[] content) throws IOException {
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("replacement-", ".xlsx");
        java.nio.file.Files.write(temp, content);
        objectStore.put(objectUri, temp, PutOptions.ofContentType(SourceFormat.XLSX.mediaType()));
        java.nio.file.Files.deleteIfExists(temp);
    }
}
