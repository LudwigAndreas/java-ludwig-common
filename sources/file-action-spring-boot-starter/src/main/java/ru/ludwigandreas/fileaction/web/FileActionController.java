package ru.ludwigandreas.fileaction.web;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.RejectView;
import ru.ludwigandreas.fileaction.engine.ResolvedAction;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.SubmissionStateException;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationResponses;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.web.PageResponse;

/**
 * The HTTP surface of every configured action, mounted once.
 *
 * <h2>One controller for every action, not one per action</h2>
 *
 * <p>The action is a path segment. A service with four imports gets four paths and writes no controller at all,
 * which is the point of the module - and it is what makes the authority check, the problem rendering and the
 * envelope identical across them rather than identical-looking.
 *
 * <h2>This is the only class in the platform that may name MultipartFile</h2>
 *
 * <p>{@code RuleGroup.UPLOADS} fails the build anywhere else. The rule exists because a hand-written upload
 * endpoint is what this module replaces, and the parts that get written badly are predictable: no ceiling before
 * the body is read, {@code getBytes()}, a DOM workbook read, and a 400 carrying three thousand row errors.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It names no entity, injects no repository and catches no checked exception. Each of those was true of an
 * earlier version and each was reported by the shared architecture rules, which were right every time: a detached
 * JPA entity in a controller is a lazy-loading failure waiting for a field to be added; a repository injected here
 * reaches past the engine into persistence; and a controller that catches an {@code IOException} has to decide
 * what it means, which is a decision that belongs where the work is. All three moved into the engine.
 */
@RestController
@RequestMapping(FileActionController.BASE_PATH)
public class FileActionController {

    /**
     * Where these endpoints live. Fixed, not configurable.
     *
     * <p>It was a property first, and two things settled it. {@code rest-paths} reads the literal annotation
     * value, and a value containing a {@code ${...}} placeholder can never match a path pattern - so the rule was
     * unsatisfiable rather than merely unsatisfied. And the rule was pointing at something real: a platform
     * starter whose endpoints move per deployment is one no client can be written against, and the only thing the
     * property bought was a status-resource URI this constant now provides directly.
     */
    public static final String BASE_PATH = "/api/v1/file-actions";

    /** How long a client is asked to wait before polling again. */
    private static final Duration RETRY_AFTER = Duration.ofSeconds(2);

    private final FileActionService service;
    private final FileActionRegistry registry;
    private final ObjectStore objectStore;
    private final ProblemMessages messages;

    /**
     * Wires the controller.
     *
     * @param service     the engine, which is the only thing this class talks to besides the object store
     * @param registry    the resolved actions
     * @param objectStore where a reject report is streamed from
     * @param messages    how a stored reject code becomes a sentence
     */
    public FileActionController(FileActionService service, FileActionRegistry registry,
                                ObjectStore objectStore, ProblemMessages messages) {
        this.service = service;
        this.registry = registry;
        this.objectStore = objectStore;
        this.messages = messages;
    }

    /**
     * Accepts a submitted file.
     *
     * <p>Answers {@code 200} with a terminal envelope when the action is {@code INLINE} and the work finished, and
     * {@code 202} with the status-resource header when it is {@code DEFERRED}. Both carry the same body type, so a
     * client does not branch on which it got - which is the whole reason the execution mode is a property rather
     * than a second endpoint.
     *
     * @param action the action name
     * @param file   the submitted file
     * @return the submission
     */
    @PostMapping(path = "/{action}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SubmissionResponse> submit(@PathVariable String action,
                                                     @RequestParam("file") MultipartFile file) {
        // file::getInputStream, never getBytes - Spring has already spooled this to disk, so getBytes would
        // copy the whole file into the heap as well, for nothing. NoMaterialisationTest fails the build on it.
        return respond(service.submit(action, file::getInputStream, file.getOriginalFilename(),
                file.getContentType()));
    }

    /**
     * Polls a submission.
     *
     * @param action the action name
     * @param id     the submission
     * @return the submission
     */
    @GetMapping("/{action}/{id}")
    public ResponseEntity<SubmissionResponse> poll(@PathVariable String action, @PathVariable UUID id) {
        SubmissionSnapshot submission = service.find(action, id);
        SubmissionResponse body = response(submission);
        if (submission.isTerminal()) {
            return ResponseEntity.ok(body);
        }
        // Retry-After on every non-terminal poll, which OperationResponses requires: a client with no hint polls
        // as fast as it can, and a hundred of them do so together.
        return OperationResponses.poll(body.operation(), body, RETRY_AFTER);
    }

    /**
     * Applies a validated submission.
     *
     * @param action the action name
     * @param id     the submission
     * @return the submission
     */
    @PostMapping("/{action}/{id}/confirm")
    public ResponseEntity<SubmissionResponse> confirm(@PathVariable String action,
                                                      @PathVariable UUID id) {
        return respond(service.confirm(action, id));
    }

    /**
     * Asks a running submission to stop.
     *
     * <p>Answers {@code 202}, not {@code 204}: cancellation is cooperative, the apply checks the flag between
     * batches, and the stop has been requested rather than achieved. A submission that has already finished
     * returns its envelope rather than a {@code 409}, because the caller's intent is already satisfied.
     *
     * @param action the action name
     * @param id     the submission
     * @return the submission
     */
    @PostMapping("/{action}/{id}/cancel")
    public ResponseEntity<SubmissionResponse> cancel(@PathVariable String action,
                                                     @PathVariable UUID id) {
        SubmissionSnapshot submission = service.cancel(action, id);
        SubmissionResponse body = response(submission);
        if (submission.isTerminal()) {
            return ResponseEntity.ok(body);
        }
        return OperationResponses.cancellationRequested(body.operation(), body);
    }

    /**
     * A page of a submission's refused rows.
     *
     * <p>Paged, and not part of the submit response. Three thousand rejects is not something a client can render
     * from one body or a person can read, which is why row rejects are not a {@code ProblemDetail}.
     *
     * @param action   the action name
     * @param id       the submission
     * @param pageable the page
     * @return the rejects
     */
    @GetMapping("/{action}/{id}/rejects")
    public PageResponse<RowRejectResponse> rejects(@PathVariable String action, @PathVariable UUID id,
                                                   Pageable pageable) {
        return PageResponse.of(service.rejects(action, id, pageable), this::toRejectResponse);
    }

    /**
     * Serves a submission's reject report.
     *
     * @param action the action name
     * @param id     the submission
     * @return the report
     */
    @GetMapping("/{action}/{id}/error-report")
    public ResponseEntity<InputStreamResource> errorReport(@PathVariable String action,
                                                           @PathVariable UUID id) {
        SubmissionSnapshot submission = service.find(action, id);
        if (submission.state() == FileActionState.EXPIRED) {
            throw new SubmissionStateException(FileActionProblemCodes.CONFIRM_WINDOW_CLOSED,
                    submission.state());
        }
        if (!submission.hasErrorReport()) {
            throw new SubmissionStateException(FileActionProblemCodes.NOT_FOUND, submission.state());
        }
        String uri = submission.errorReportUri();
        boolean workbook = uri.endsWith("." + SourceFormat.XLSX.extension());
        SourceFormat format = workbook ? SourceFormat.XLSX : SourceFormat.CSV;
        // Streamed from the store rather than buffered: a reject report for a large file is itself large, and
        // reading it into memory to write it out again would reintroduce the cost the whole module avoids.
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("rejects-" + submission.id() + "." + format.extension())
                        .build().toString())
                .contentType(MediaType.parseMediaType(format.mediaType()))
                .body(new InputStreamResource(objectStore.open(uri)));
    }

    /**
     * A blank workbook whose headings come from the action's binding.
     *
     * <p>The cheapest usability win available: the headings are generated from the same binding the reader
     * validates against, so the two cannot disagree - which a template maintained by hand in a wiki can and does.
     *
     * @param action the action name
     * @return the template
     */
    @GetMapping("/{action}/template")
    public ResponseEntity<byte[]> template(@PathVariable String action) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(action + "-template." + SourceFormat.XLSX.extension())
                        .build().toString())
                .contentType(MediaType.parseMediaType(SourceFormat.XLSX.mediaType()))
                .body(service.template(action));
    }

    private ResponseEntity<SubmissionResponse> respond(SubmissionSnapshot submission) {
        SubmissionResponse body = response(submission);
        if (submission.isTerminal()) {
            return OperationResponses.completed(body.operation(), body);
        }
        return OperationResponses.accepted(body.operation(), body, statusUri(submission),
                OperationLocationHeader.OPERATION_LOCATION);
    }

    private SubmissionResponse response(SubmissionSnapshot submission) {
        ResolvedAction<?> action = registry.require(submission.action());
        return SubmissionMapper.toResponse(submission, action, service.rejectsStored(submission.id()),
                statusUri(submission).toString());
    }

    private URI statusUri(SubmissionSnapshot submission) {
        return URI.create(BASE_PATH + "/" + submission.action() + "/" + submission.id());
    }

    private RowRejectResponse toRejectResponse(RejectView reject) {
        // Rendered on read, in the reader's locale, from the stored code and arguments. A reject stored as a
        // sentence would be frozen in whatever locale the submitting request carried, and a bundle correction
        // would never reach anything already stored.
        String message = messages.get(reject.code(), reject.args().toArray(), reject.code());
        return new RowRejectResponse(reject.sheet(), reject.row(), reject.column(), reject.code(),
                message, reject.args());
    }
}
