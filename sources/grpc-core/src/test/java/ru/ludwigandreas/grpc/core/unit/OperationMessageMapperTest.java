package ru.ludwigandreas.grpc.core.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.ludwigandreas.grpc.core.operation.OperationMessages;
import ru.ludwigandreas.operation.grpc.v1.Operation;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

class OperationMessageMapperTest {

    @Test
    @DisplayName("every platform status has its own wire value, and none is the unspecified one")
    void everyStatusHasADistinctWireValue() {
        Set<ru.ludwigandreas.operation.grpc.v1.OperationStatus> wire = EnumSet.allOf(OperationStatus.class).stream()
                .map(OperationMessages::toWire)
                .collect(Collectors.toSet());

        assertThat(wire).hasSize(OperationStatus.values().length)
                .doesNotContain(ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_UNSPECIFIED,
                        ru.ludwigandreas.operation.grpc.v1.OperationStatus.UNRECOGNIZED);
    }

    @ParameterizedTest
    @EnumSource(OperationStatus.class)
    @DisplayName("a status reads back as itself")
    void statusRoundTrips(OperationStatus status) {
        assertThat(OperationMessages.fromWire(OperationMessages.toWire(status))).isEqualTo(status);
    }

    @Test
    @DisplayName("the wire enum has exactly the six states plus the two protobuf adds")
    void wireEnumHasNoExtraState() {
        // If a seventh state is added to the proto and not to web-core, this is where it shows.
        assertThat(ru.ludwigandreas.operation.grpc.v1.OperationStatus.values())
                .hasSize(OperationStatus.values().length + 2);
    }

    @Test
    @DisplayName("an unset status is refused rather than read as PENDING")
    void unspecifiedIsRejected() {
        assertThatThrownBy(() -> OperationMessages.fromWire(
                ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_UNSPECIFIED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperationMessages.fromMessage(Operation.newBuilder().setId("op-1").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a running operation keeps its detail, progress, timestamps and retry-after")
    void runningOperationRoundTrips() {
        OperationResponse running = OperationResponse.builder()
                .id("op-1")
                .status(OperationStatus.RUNNING)
                .detail("AWAITING_PARTNER")
                .progress(OperationProgress.of(40, 100, "rows").inPhase("enrich"))
                .submittedAt(Instant.parse("2026-10-11T10:00:00.123456789Z"))
                .startedAt(Instant.parse("2026-10-11T10:00:01Z"))
                .correlationId("corr-1")
                .build();

        Operation message = OperationMessages.toMessage(running, Duration.ofSeconds(5));

        assertThat(message.getStatus())
                .isEqualTo(ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_RUNNING);
        assertThat(OperationMessages.retryAfter(message)).isEqualTo(Duration.ofSeconds(5));
        assertThat(OperationMessages.fromMessage(message)).isEqualTo(running);
    }

    @Test
    @DisplayName("a zero total is a total, and an unknown total stays unknown")
    void totalPresenceIsPreserved() {
        OperationResponse unknownTotal = OperationResponse.builder().id("op-2").status(OperationStatus.PENDING)
                .progress(OperationProgress.of(0, "rows")).build();
        OperationResponse zeroTotal = OperationResponse.builder().id("op-3").status(OperationStatus.SUCCEEDED)
                .progress(OperationProgress.of(0, 0, "rows")).build();

        assertThat(OperationMessages.fromMessage(OperationMessages.toMessage(unknownTotal, null)).progress().total())
                .isNull();
        assertThat(OperationMessages.fromMessage(OperationMessages.toMessage(zeroTotal, null)).progress().total())
                .isZero();
    }

    @Test
    @DisplayName("a terminal success keeps its result and alternatives and states no retry-after")
    void succeededOperationRoundTrips() {
        OperationResponse succeeded = OperationResponse.builder()
                .id("op-4")
                .status(OperationStatus.SUCCEEDED)
                .finishedAt(Instant.parse("2026-10-11T10:05:00Z"))
                .result(OperationResult.at("/reports/4", "text/csv", 1024L)
                        .withAlternatives(List.of(OperationResult.at("/reports/4.xlsx"))))
                .build();

        Operation message = OperationMessages.toMessage(succeeded, null);

        assertThat(message.hasRetryAfter()).isFalse();
        assertThat(OperationMessages.retryAfter(message)).isNull();
        assertThat(OperationMessages.fromMessage(message)).isEqualTo(succeeded);
    }

    @Test
    @DisplayName("a failure keeps its code, detail and arguments")
    void failedOperationRoundTrips() {
        OperationResponse failed = OperationResponse.builder()
                .id("op-5")
                .status(OperationStatus.FAILED)
                .failure(new OperationFailure("export.error.too-large", "over the row limit", List.of("1000000")))
                .build();

        assertThat(OperationMessages.fromMessage(OperationMessages.toMessage(failed, null))).isEqualTo(failed);
    }
}
