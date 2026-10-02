package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.exception.SubmissionNotFoundException;
import ru.ludwigandreas.fileaction.exception.SubmissionStateException;
import ru.ludwigandreas.fileaction.exception.UnknownActionException;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Each failure carries the status a client should act on, and a code the bundle can render.
 *
 * <p>The statuses are the interesting half. A file that is too large is a 413 and not a 400, so a gateway can
 * recognise it; an unknown action is a 404 because the action is a path segment; a confirm of something already
 * applied is a 409 because the request is in conflict with the resource's state rather than malformed. Getting
 * these wrong is the difference between a client that can retry sensibly and one that treats everything as a bug.
 */
class ProblemMapperTest {

    @Test
    @DisplayName("a file over the ceiling is 413, so a gateway can tell it from a malformed request")
    void tooLargeIsPayloadTooLarge() {
        ProblemDefinition problem = new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                FileActionProblemCodes.TOO_LARGE, 1024).toDefinition();

        assertThat(problem.status()).isEqualTo(ProblemStatus.PAYLOAD_TOO_LARGE);
        assertThat(problem.code()).isEqualTo(FileActionProblemCodes.TOO_LARGE);
        assertThat(problem.args()).containsExactly(1024);
    }

    @Test
    @DisplayName("a legacy .xls is 415, because the format is the problem rather than the content")
    void legacyXlsIsUnsupportedMediaType() {
        ProblemDefinition problem = new FileRejectedException(ProblemStatus.UNSUPPORTED_MEDIA_TYPE,
                FileActionProblemCodes.LEGACY_XLS, "orders.xls").toDefinition();

        assertThat(problem.status()).isEqualTo(ProblemStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(problem.code()).isEqualTo(FileActionProblemCodes.LEGACY_XLS);
    }

    @Test
    @DisplayName("an unknown action is 404, because the action is a path segment")
    void unknownActionIsNotFound() {
        ProblemDefinition problem = new UnknownActionException("nope").toDefinition();

        assertThat(problem.status()).isEqualTo(ProblemStatus.NOT_FOUND);
        assertThat(problem.code()).isEqualTo(FileActionProblemCodes.UNKNOWN_ACTION);
        assertThat(problem.args()).containsExactly("nope");
    }

    @Test
    @DisplayName("an unknown submission is 404 and names both the action and the id")
    void unknownSubmissionIsNotFound() {
        UUID id = UUID.randomUUID();
        ProblemDefinition problem = new SubmissionNotFoundException("order-import", id).toDefinition();

        assertThat(problem.status()).isEqualTo(ProblemStatus.NOT_FOUND);
        assertThat(problem.args()).containsExactly("order-import", id);
    }

    @Test
    @DisplayName("acting on a submission in the wrong state is 409, not 400")
    void wrongStateIsConflict() {
        ProblemDefinition problem = new SubmissionStateException(FileActionProblemCodes.WRONG_STATE,
                FileActionState.APPLIED).toDefinition();

        assertThat(problem.status())
                .as("the request is in conflict with the resource's state rather than malformed")
                .isEqualTo(ProblemStatus.CONFLICT);
        assertThat(problem.args()).containsExactly("APPLIED");
    }

    @Test
    @DisplayName("a closed confirm window carries its own code, not a generic conflict")
    void confirmWindowClosedHasItsOwnCode() {
        ProblemDefinition problem = new SubmissionStateException(
                FileActionProblemCodes.CONFIRM_WINDOW_CLOSED, FileActionState.EXPIRED).toDefinition();

        assertThat(problem.code())
                .as("the user has to be told to upload the file again, which a generic message cannot say")
                .isEqualTo(FileActionProblemCodes.CONFIRM_WINDOW_CLOSED);
    }

    @Test
    @DisplayName("a scanner refusal does not leak what the scanner found")
    void scanRefusalDoesNotLeakTheSignature() {
        ProblemDefinition problem = new FileRejectedException(ProblemStatus.INVALID,
                FileActionProblemCodes.SCAN_REJECTED, "orders.xlsx").toDefinition();

        assertThat(problem.args())
                .as("the submitting user does not need to know which signature matched and should not be told;"
                        + " the signature goes to the audit record instead")
                .containsExactly("orders.xlsx");
    }
}
