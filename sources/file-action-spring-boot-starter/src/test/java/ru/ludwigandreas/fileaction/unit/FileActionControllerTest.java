package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.web.FileActionController;
import ru.ludwigandreas.fileaction.web.SubmissionResponse;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.webcore.operation.OperationHeaders;
import ru.ludwigandreas.webcore.operation.OperationStatus;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * The HTTP contract: which status code, which headers, and what a terminal envelope has to carry.
 *
 * <p>The first two cases are the point of the whole execution-mode decision. An {@code INLINE} action answers 200
 * with a terminal envelope and a {@code DEFERRED} one answers 202 with a status-resource header, and the body type
 * is the same for both - so a deployment that switches an action from one to the other changes no client. Had the
 * two been separate endpoints, that switch would have been a breaking API change, which is the kind of change
 * nobody makes and everybody lives with.
 */
class FileActionControllerTest {

    private FileActionService service;
    private FileActionController controller;

    @BeforeEach
    void setUp() {
        service = mock(FileActionService.class);
        FileActionRegistry registry = new FileActionRegistry(java.util.List.of(
                TestActions.action(new AppliesEverything(), CommitPolicy.PER_ROW, 100)));
        ProblemMessages messages = ProblemMessages.ofBundles("i18n/ludwig-file-action-messages");
        controller = new FileActionController(service, registry, mock(ObjectStore.class), messages);
        when(service.rejectsStored(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("an INLINE action that finished answers 200 with a terminal envelope")
    void inlineAnswersTwoHundred() {
        when(service.find(anyString(), any())).thenReturn(applied());

        ResponseEntity<SubmissionResponse> response = controller.poll("order-import", applied().id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().operation().status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(response.getBody().operation().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a submit of a DEFERRED action answers 202 with the status-resource header")
    void deferredAnswersTwoHundredAndTwo() {
        when(service.submit(anyString(), any(), any(), any())).thenReturn(uploaded());

        ResponseEntity<SubmissionResponse> response = controller.submit("order-import",
                multipart("orders.xlsx"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst(OperationHeaders.OPERATION_LOCATION))
                .as("a 202 with nowhere to go is the failure the operation contract exists to stop")
                .isEqualTo(FileActionController.BASE_PATH + "/order-import/" + uploaded().id());
        assertThat(response.getBody().operation().status()).isEqualTo(OperationStatus.PENDING);
    }

    @Test
    @DisplayName("both modes answer with the same body type, so a client does not branch on which it got")
    void bothModesShareABodyType() {
        when(service.submit(anyString(), any(), any(), any())).thenReturn(uploaded());
        ResponseEntity<SubmissionResponse> deferred = controller.submit("order-import",
                multipart("orders.xlsx"));

        when(service.submit(anyString(), any(), any(), any())).thenReturn(applied());
        ResponseEntity<SubmissionResponse> inline = controller.submit("order-import",
                multipart("orders.xlsx"));

        assertThat(inline.getBody()).isInstanceOf(SubmissionResponse.class);
        assertThat(deferred.getBody()).isInstanceOf(SubmissionResponse.class);
        assertThat(inline.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deferred.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a non-terminal poll carries Retry-After")
    void nonTerminalPollCarriesRetryAfter() {
        when(service.find(anyString(), any())).thenReturn(uploaded());

        ResponseEntity<SubmissionResponse> response = controller.poll("order-import", uploaded().id());

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("a client with no hint polls as fast as it can, and a hundred of them do so together")
                .isNotNull();
    }

    @Test
    @DisplayName("a terminal poll does not carry Retry-After, because there is nothing to come back for")
    void terminalPollDoesNot() {
        when(service.find(anyString(), any())).thenReturn(applied());

        ResponseEntity<SubmissionResponse> response = controller.poll("order-import", applied().id());

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    @DisplayName("cancelling a running submission answers 202, because the stop is requested not achieved")
    void cancelAnswersTwoHundredAndTwo() {
        when(service.cancel(anyString(), any())).thenReturn(uploaded());

        ResponseEntity<SubmissionResponse> response = controller.cancel("order-import", uploaded().id());

        assertThat(response.getStatusCode())
                .as("204 would claim the submission has stopped; it has only been asked to")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("cancelling an already-applied submission returns the envelope, not a 409")
    void cancelOfATerminalSubmissionIsNotAConflict() {
        when(service.cancel(anyString(), any())).thenReturn(applied());

        ResponseEntity<SubmissionResponse> response = controller.cancel("order-import", applied().id());

        assertThat(response.getStatusCode())
                .as("the caller's intent - make sure this is not running - is already satisfied, and an error"
                        + " would make them handle a success")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a validated submission reports that it is waiting for a person, which the status cannot say")
    void validatedReportsAwaitingConfirmation() {
        when(service.find(anyString(), any())).thenReturn(validated());

        SubmissionResponse body = controller.poll("order-import", validated().id()).getBody();

        assertThat(body.operation().status()).isEqualTo(OperationStatus.PENDING);
        assertThat(body.operation().detail()).isEqualTo("VALIDATED");
        assertThat(body.confirmBy()).isNotNull();
    }

    @Test
    @DisplayName("the template endpoint serves a workbook as an attachment")
    void templateIsAnAttachment() {
        when(service.template("order-import")).thenReturn(new byte[] {1, 2, 3});

        ResponseEntity<byte[]> response = controller.template("order-import");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("order-import-template.xlsx");
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo(SourceFormat.XLSX.mediaType());
    }

    @Test
    @DisplayName("a refused submission carries its failure code and no result link")
    void refusedCarriesItsFailure() {
        when(service.find(anyString(), any())).thenReturn(rejected());

        SubmissionResponse body = controller.poll("order-import", rejected().id()).getBody();

        assertThat(body.operation().status()).isEqualTo(OperationStatus.FAILED);
        assertThat(body.operation().failure().code()).isEqualTo("file-action.too-many-rejects");
        assertThat(body.operation().result())
                .as("OperationResponses refuses a terminal success with no result, and a refusal has none")
                .isNull();
    }

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static SubmissionSnapshot uploaded() {
        return snapshot(FileActionState.UPLOADED, null, null);
    }

    private static SubmissionSnapshot validated() {
        return snapshot(FileActionState.VALIDATED, null, Instant.parse("2026-02-01T12:00:00Z"));
    }

    private static SubmissionSnapshot applied() {
        return snapshot(FileActionState.APPLIED, null, null);
    }

    private static SubmissionSnapshot rejected() {
        return snapshot(FileActionState.REJECTED, "file-action.too-many-rejects", null);
    }

    private static SubmissionSnapshot snapshot(FileActionState state, String failureCode,
                                               Instant expiresAt) {
        Instant submitted = Instant.parse("2026-02-01T11:00:00Z");
        Instant finished = state.isTerminal() ? Instant.parse("2026-02-01T11:00:05Z") : null;
        return new SubmissionSnapshot(ID, "order-import", state, "orders.xlsx", SourceFormat.XLSX,
                "Orders", 400, state == FileActionState.APPLIED ? 400 : 0, 0, 0, failureCode, null, null,
                submitted, submitted, finished, expiresAt, "corr-1");
    }

    private static org.springframework.web.multipart.MultipartFile multipart(String filename) {
        return new org.springframework.mock.web.MockMultipartFile("file", filename,
                SourceFormat.XLSX.mediaType(), new byte[] {1, 2, 3});
    }

    /** A handler, so the registry has a resolved action to look up. */
    private static final class AppliesEverything
            implements ru.ludwigandreas.fileaction.api.RowHandler<OrderLine> {

        @Override
        public ru.ludwigandreas.fileaction.api.RowBinding<OrderLine> binding() {
            return TestActions.BINDING;
        }

        @Override
        public ru.ludwigandreas.fileaction.api.RowOutcome apply(OrderLine row,
                ru.ludwigandreas.fileaction.api.FileActionContext context) {
            return ru.ludwigandreas.fileaction.api.RowOutcome.applied();
        }
    }
}
