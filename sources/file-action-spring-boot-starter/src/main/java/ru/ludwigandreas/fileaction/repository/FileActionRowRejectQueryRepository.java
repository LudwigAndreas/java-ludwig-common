package ru.ludwigandreas.fileaction.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.fileaction.entity.FileActionRowRejectEntity;

/** The queries the rejects endpoint runs. */
public interface FileActionRowRejectQueryRepository {

    /**
     * A page of a submission's rejects, in file order.
     *
     * <p>Paged rather than returned whole, which is the reason row rejects are not a {@code ProblemDetail}:
     * a response carrying three thousand entries is not something a client can render or a person can read.
     *
     * @param submissionId the submission
     * @param pageable     the page
     * @return the page, ordered by the row number as the spreadsheet displays it
     */
    Page<FileActionRowRejectEntity> findBySubmission(UUID submissionId, Pageable pageable);

    /**
     * How many rejects are stored for a submission.
     *
     * <p>Distinct from the submission's own {@code rowsRejected}: that is how many rows were refused, this is
     * how many of them the bounded sample kept. A client comparing the two is how the UI knows to say
     * "showing the first hundred of three thousand".
     *
     * @param submissionId the submission
     * @return the stored count
     */
    long countBySubmission(UUID submissionId);

    /**
     * Deletes a submission's stored rejects.
     *
     * @param submissionId the submission
     * @return how many were deleted
     */
    long deleteBySubmission(UUID submissionId);
}
