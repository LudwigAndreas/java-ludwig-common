package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationHeaders;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResponses;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The helpers refuse the malformed responses. Each rejection below is a response shape this platform
 * actually shipped before the contract existed.
 */
class OperationResponsesTest {

    private static final URI STATUS_URI = URI.create("/api/v1/reports/runs/42");

    private static OperationResponse at(OperationStatus status) {
        return OperationResponse.builder()
                .id("42")
                .status(status)
                .submittedAt(Instant.parse("2026-09-27T09:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("a 202 carries a Location and a non-terminal envelope")
    void acceptedCarriesLocation() {
        ResponseEntity<OperationResponse> response = OperationResponses.accepted(
                at(OperationStatus.PENDING), STATUS_URI, OperationLocationHeader.LOCATION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo(STATUS_URI.toString());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Operation-Location is the other choice, and the caller has to make one")
    void acceptedCanUseOperationLocation() {
        ResponseEntity<OperationResponse> response = OperationResponses.accepted(
                at(OperationStatus.RUNNING), STATUS_URI, OperationLocationHeader.OPERATION_LOCATION);

        assertThat(response.getHeaders().getFirst(OperationHeaders.OPERATION_LOCATION))
                .isEqualTo(STATUS_URI.toString());
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();

        assertThatThrownBy(() -> OperationResponses.accepted(
                at(OperationStatus.PENDING), STATUS_URI, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a 202 without a status resource is refused - export shipped exactly this")
    void acceptedWithoutLocationIsRefused() {
        assertThatThrownBy(() -> OperationResponses.accepted(
                at(OperationStatus.PENDING), null, OperationLocationHeader.LOCATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status resource");
    }

    @Test
    @DisplayName("a 202 may carry a terminal envelope - notification fans out before it answers")
    void acceptedMayBeTerminal() {
        OperationResponse finished = at(OperationStatus.SUCCEEDED).toBuilder()
                .result(OperationResult.at("/api/v1/notifications/42"))
                .build();

        ResponseEntity<OperationResponse> response = OperationResponses.accepted(
                finished, STATUS_URI, OperationLocationHeader.LOCATION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo(STATUS_URI.toString());
    }

    @Test
    @DisplayName("but a terminal 202 still owes its evidence")
    void terminalAcceptedStillNeedsAResult() {
        assertThatThrownBy(() -> OperationResponses.accepted(
                at(OperationStatus.SUCCEEDED), STATUS_URI, OperationLocationHeader.LOCATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result");
    }

    @Test
    @DisplayName("the synchronous fast path returns 200 with a terminal envelope")
    void completedReturnsTerminalEnvelope() {
        OperationResponse finished = at(OperationStatus.SUCCEEDED).toBuilder()
                .result(OperationResult.at("/api/v1/reports/runs/42/outputs/csv"))
                .finishedAt(Instant.parse("2026-09-27T09:00:05Z"))
                .build();

        ResponseEntity<OperationResponse> response = OperationResponses.completed(finished);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(OperationHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a 200 from a submit endpoint must not carry work that is still running")
    void completedMustBeTerminal() {
        assertThatThrownBy(() -> OperationResponses.completed(at(OperationStatus.RUNNING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("a non-terminal poll carries Retry-After")
    void pollCarriesRetryAfter() {
        ResponseEntity<OperationResponse> response =
                OperationResponses.poll(at(OperationStatus.RUNNING), Duration.ofSeconds(5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(OperationHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    @DisplayName("a non-terminal poll without Retry-After is refused")
    void pollWithoutRetryAfterIsRefused() {
        assertThatThrownBy(() -> OperationResponses.poll(at(OperationStatus.PENDING), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Retry-After");
        assertThatThrownBy(() ->
                OperationResponses.poll(at(OperationStatus.PENDING), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a terminal poll carries no Retry-After and carries a result or a failure")
    void terminalPollCarriesEvidenceAndNoRetryAfter() {
        OperationResponse failed = at(OperationStatus.FAILED).toBuilder()
                .failure(OperationFailure.of("ludwig.web.error.internal"))
                .build();

        ResponseEntity<OperationResponse> response =
                OperationResponses.poll(failed, Duration.ofSeconds(5));

        assertThat(response.getHeaders().getFirst(OperationHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    @DisplayName("a terminal success with nothing to fetch is a producer bug")
    void terminalSuccessNeedsAResult() {
        assertThatThrownBy(() ->
                OperationResponses.poll(at(OperationStatus.SUCCEEDED), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result");
    }

    @Test
    @DisplayName("a terminal failure with nothing to report is a producer bug")
    void terminalFailureNeedsAFailure() {
        assertThatThrownBy(() ->
                OperationResponses.poll(at(OperationStatus.FAILED), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure");
    }

    @Test
    @DisplayName("CANCELLED and EXPIRED are terminal without owing a result or a failure")
    void cancelledAndExpiredOweNothing() {
        assertThat(OperationResponses.poll(at(OperationStatus.CANCELLED), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(OperationResponses.poll(at(OperationStatus.EXPIRED), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("cancellation answers 202, and a second cancel on a terminal one is not a 409")
    void cancellationIsAlwaysAccepted() {
        assertThat(OperationResponses.cancellationRequested(at(OperationStatus.RUNNING))
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<OperationResponse> second =
                OperationResponses.cancellationRequested(at(OperationStatus.CANCELLED));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().status()).isEqualTo(OperationStatus.CANCELLED);
    }

    @Test
    @DisplayName("a module's own body is written, and the envelope is what the contract checks")
    void aModuleBodyIsCarriedThrough() {
        record RunBody(String id, OperationResponse operation) {
        }

        OperationResponse envelope = at(OperationStatus.PENDING);
        RunBody body = new RunBody("42", envelope);

        ResponseEntity<RunBody> response = OperationResponses.accepted(
                envelope, body, STATUS_URI, OperationLocationHeader.LOCATION);

        assertThat(response.getBody()).isSameAs(body);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo(STATUS_URI.toString());
    }
}
