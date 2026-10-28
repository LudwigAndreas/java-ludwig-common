package ru.ludwigandreas.fileaction.admin.web;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.fileaction.admin.FileActionAdminOperations;
import ru.ludwigandreas.fileaction.admin.service.FileActionAdminService;
import ru.ludwigandreas.fileaction.admin.service.FileActionAdminService.Submission;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.web.SubmissionMapper;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminPage;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResponses;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The {@code fileactions} administrative area: every submitter's submissions, and cancellation.
 */
@RestController
@RequestMapping("/admin/v1/fileactions/submissions")
public class FileActionAdminController {

    private final FileActionAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public FileActionAdminController(FileActionAdminService service) {
        this.service = service;
    }

    /**
     * Submissions matching the filter, newest first.
     *
     * @param action    the action
     * @param submitter who submitted it
     * @param status    its envelope status
     * @param cursor    the previous page's cursor
     * @param size      the page size, at most 200
     * @return one page
     */
    @GetMapping
    @AdminAccess(FileActionAdminOperations.READ)
    public AdminPage<SubmissionView> submissions(@RequestParam(required = false) String action,
                                                 @RequestParam(required = false) String submitter,
                                                 @RequestParam(required = false) OperationStatus status,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer size) {
        AdminPage<Submission> page = service.submissions(action, submitter, status, cursor, size);
        return new AdminPage<>(page.items().stream().map(SubmissionView::of).toList(), page.next());
    }

    /**
     * One submission: its envelope, submitter and counts, without the file or any row.
     *
     * @param id the submission
     * @return the submission
     */
    @GetMapping("/{id}")
    @AdminAccess(FileActionAdminOperations.READ)
    public SubmissionView submission(@PathVariable UUID id) {
        return SubmissionView.of(service.submission(id));
    }

    /**
     * Cancels anybody's submission; a terminal one answers with its envelope.
     *
     * @param id the submission
     * @return {@code 202} with the submission, or {@code 200} when it was already terminal
     */
    @PostMapping("/{id}/cancel")
    @AdminAccess(FileActionAdminOperations.OPERATE)
    public ResponseEntity<SubmissionView> cancel(@PathVariable UUID id) {
        SubmissionView view = SubmissionView.of(service.cancel(id));
        if (view.operation().isTerminal()) {
            return ResponseEntity.ok(view);
        }
        return OperationResponses.cancellationRequested(view.operation(), view);
    }

    /**
     * One submission for an administrator. No error report link: the report lists rejected rows' contents.
     *
     * @param operation    its envelope, without a result link
     * @param id           the submission
     * @param action       the action
     * @param submitter    who submitted it
     * @param filename     the declared filename
     * @param rowsRead     rows read
     * @param rowsApplied  rows applied
     * @param rowsRejected rows rejected
     * @param rowsSkipped  rows skipped
     * @param failureCode  why it failed, if it did
     * @param submittedAt  when
     * @param finishedAt   when it ended
     */
    public record SubmissionView(OperationResponse operation, UUID id, String action, String submitter,
                                 String filename, long rowsRead, long rowsApplied, long rowsRejected,
                                 long rowsSkipped, String failureCode, Instant submittedAt, Instant finishedAt) {

        static SubmissionView of(Submission submission) {
            SubmissionSnapshot s = submission.snapshot();
            return new SubmissionView(SubmissionMapper.toOperation(s, null), s.id(), s.action(),
                    submission.submitter(), s.filename(), s.rowsRead(), s.rowsApplied(), s.rowsRejected(),
                    s.rowsSkipped(), s.failureCode(), s.submittedAt(), s.finishedAt());
        }
    }
}
