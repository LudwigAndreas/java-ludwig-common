package ru.ludwigandreas.fileaction.admin.service;

import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import ru.ludwigandreas.db.core.query.AdminQueryFilter;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.SubmissionSnapshot;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.exception.SubmissionNotFoundException;
import ru.ludwigandreas.fileaction.repository.FileActionAdminQueries;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminCursor;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.AdminPage;

/**
 * The {@code fileactions} area's operations: every submitter's submissions and the means to cancel one, without
 * exposing the uploaded file or any rejected row - those are the submitter's data.
 */
public class FileActionAdminService {

    private static final String RESOURCE = "file-action-submission";

    private final FileActionSubmissionRepository submissions;
    private final FileActionService actions;
    private final AdminQueryFilter filters;
    private final AdminAudit audit;

    /**
     * Creates the service.
     *
     * @param submissions the submission table
     * @param actions     the file-action engine, whose cancel this reuses
     * @param filters     parses the submissions listing's {@code $filter}
     * @param audit       the administrative audit
     */
    public FileActionAdminService(FileActionSubmissionRepository submissions, FileActionService actions,
                                  AdminQueryFilter filters, AdminAudit audit) {
        this.filters = filters;
        this.submissions = submissions;
        this.actions = actions;
        this.audit = audit;
    }

    /**
     * One page of submissions, newest first.
     *
     * @param rawFilter an OData {@code $filter} over the submission's filterable fields, or null
     * @param cursor    the previous page's cursor, or null
     * @param size      the page size, or null
     * @return the page
     */
    public AdminPage<Submission> submissions(String rawFilter, String cursor, Integer size) {
        int pageSize = AdminCursor.pageSize(size);
        Instant afterSubmittedAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = AdminCursor.decode(cursor, 2);
            afterSubmittedAt = Instant.parse(position.get(0));
            afterId = UUID.fromString(position.get(1));
        }
        Predicate filter = filters.parse(FileActionSubmissionEntity.class, rawFilter).where().orElse(null);
        List<Submission> rows = submissions.findAll(
                        FileActionAdminQueries.submissions(filter, afterSubmittedAt, afterId),
                        PageRequest.of(0, pageSize + 1,
                                Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id"))))
                .stream().map(Submission::of).toList();
        return AdminPage.of(rows, pageSize, row -> AdminCursor.encode(row.snapshot().submittedAt().toString(),
                row.snapshot().id().toString()));
    }

    /**
     * One submission.
     *
     * @param id the submission
     * @return the submission
     */
    public Submission submission(UUID id) {
        return Submission.of(submissions.findById(id).orElseThrow(() -> new SubmissionNotFoundException("*", id)));
    }

    /**
     * Cancels anybody's submission exactly as the submitter's own cancel does.
     *
     * @param id the submission
     * @return the submission as it now stands
     */
    public Submission cancel(UUID id) {
        FileActionSubmissionEntity before = submissions.findById(id)
                .orElseThrow(() -> new SubmissionNotFoundException("*", id));
        boolean wasTerminal = before.isTerminal();
        SubmissionSnapshot after = actions.cancelByAdministrator(id);
        audit.record(RESOURCE, id.toString(), wasTerminal ? AdminOutcome.REFUSED_BY_STATE : AdminOutcome.SUCCEEDED,
                Map.of("submitter", before.getSubmittedBy(), "action", before.getAction()));
        return new Submission(after, before.getSubmittedBy());
    }

    /**
     * A submission with its submitter, which the submitter-facing snapshot leaves out.
     *
     * @param snapshot  the submission
     * @param submitter who submitted it
     */
    public record Submission(SubmissionSnapshot snapshot, String submitter) {

        static Submission of(FileActionSubmissionEntity entity) {
            return new Submission(SubmissionSnapshot.of(entity), entity.getSubmittedBy());
        }
    }
}
