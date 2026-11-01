package ru.ludwigandreas.fileaction.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code fileaction} administrative area: every submitter's file-action submissions, and cancelling one.
 */
public final class FileActionAdminOperations implements OperationContributor {

    /** See every submitter's submissions, without the file or any row. */
    public static final String SUBMISSION_READ = "fileaction.submission.read";

    /** Cancel anybody's submission. */
    public static final String SUBMISSION_CANCEL = "fileaction.submission.cancel";

    private static final String KEY = "ludwig.file-action.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(SUBMISSION_READ, Sensitivity.READ, KEY + SUBMISSION_READ),
                Operation.admin(SUBMISSION_CANCEL, Sensitivity.CHANGE, KEY + SUBMISSION_CANCEL));
    }
}
