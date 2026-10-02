package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * That the module's domain lifecycle maps onto the platform's one status vocabulary and does not
 * restate it.
 *
 * <p>{@code RuleGroup.OPERATIONS} is the build-failing check; this test states the mapping itself,
 * which an ArchUnit rule cannot see.
 */
class FileActionStateMappingTest {

    @ParameterizedTest
    @EnumSource(FileActionState.class)
    @DisplayName("every domain state maps onto a core status")
    void everyStateMaps(FileActionState state) {
        assertThat(state.status()).isNotNull();
    }

    @Test
    @DisplayName("not every constant is a word from the shared vocabulary, which is what RuleGroup.OPERATIONS tests")
    void isARicherLifecycleRatherThanARestatement() {
        Set<String> sharedWords = Set.of(
                "PENDING", "RUNNING", "QUEUED", "IN_PROGRESS", "INPROGRESS", "STARTED", "ACCEPTED",
                "WAITING", "SCHEDULED", "NEW", "CREATED", "SUBMITTED", "ACTIVE", "PROCESSING",
                "SUCCEEDED", "SUCCESS", "SUCCESSFUL", "COMPLETED", "COMPLETE", "DONE", "FINISHED",
                "OK", "FAILED", "FAILURE", "ERROR", "ERRORED", "REJECTED", "CANCELLED", "CANCELED",
                "EXPIRED", "ABORTED", "TIMED_OUT", "TIMEDOUT", "TIMEOUT", "STOPPED", "INTERRUPTED");

        assertThat(Arrays.stream(FileActionState.values())
                .map(Enum::name)
                .filter(name -> !sharedWords.contains(name)))
                .as("OperationVocabularyRules flags an enum only when EVERY constant is a word from the"
                        + " shared vocabulary; the constants carrying information the core cannot"
                        + " express are what make this a lifecycle that maps onto the vocabulary"
                        + " rather than a second spelling of it")
                .containsExactly("UPLOADED", "VALIDATED", "APPLYING", "APPLIED");
    }

    @Test
    @DisplayName("REJECTED and EXPIRED deliberately reuse core words, which the rule permits")
    void reusingTwoCoreWordsIsNotARestatement() {
        assertThat(FileActionState.REJECTED.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(FileActionState.EXPIRED.status()).isEqualTo(OperationStatus.EXPIRED);
    }

    @Test
    @DisplayName("UPLOADED and VALIDATED share a core status, which is why the domain state is published")
    void theConfirmDistinctionIsInvisibleFromTheCoreStatusAlone() {
        assertThat(FileActionState.UPLOADED.status()).isEqualTo(OperationStatus.PENDING);
        assertThat(FileActionState.VALIDATED.status()).isEqualTo(OperationStatus.PENDING);
        assertThat(FileActionState.VALIDATED.awaitsHuman()).isTrue();
        assertThat(FileActionState.UPLOADED.awaitsHuman()).isFalse();
    }

    @Test
    @DisplayName("VALIDATED is PENDING, not RUNNING: nothing is executing while it waits for a person")
    void validatedIsNotRunning() {
        assertThat(FileActionState.VALIDATED.status()).isNotEqualTo(OperationStatus.RUNNING);
    }

    @Test
    @DisplayName("EXPIRED is terminal and is not a failure")
    void expiredIsNotAFailure() {
        assertThat(FileActionState.EXPIRED.isTerminal()).isTrue();
        assertThat(FileActionState.EXPIRED.status().isFailure()).isFalse();
    }

    @Test
    @DisplayName("REJECTED is the only domain state that is a failure")
    void onlyRejectedIsAFailure() {
        assertThat(Arrays.stream(FileActionState.values())
                .filter(state -> state.status().isFailure()))
                .containsExactly(FileActionState.REJECTED);
    }

    @Test
    @DisplayName("the non-terminal states are exactly the two a client keeps polling")
    void nonTerminalStates() {
        assertThat(Arrays.stream(FileActionState.values()).filter(s -> !s.isTerminal()))
                .containsExactly(FileActionState.UPLOADED, FileActionState.VALIDATED,
                        FileActionState.APPLYING);
    }
}
