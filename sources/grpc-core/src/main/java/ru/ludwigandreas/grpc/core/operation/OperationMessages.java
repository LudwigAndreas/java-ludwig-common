package ru.ludwigandreas.grpc.core.operation;

import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import ru.ludwigandreas.operation.grpc.v1.Operation;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The one conversion between web-core's operation envelope and its wire form.
 *
 * <p>A gRPC method that starts, polls or cancels a long-running operation returns
 * {@link Operation}, built here from the same {@link OperationResponse} the module would have
 * handed to {@code OperationResponses} for HTTP. There is one envelope and two renderings of it;
 * the module's own run table, its richer lifecycle in {@code detail} and the absence of any shared
 * operation table are all exactly as they were.
 *
 * <h2>Why both status switches name every constant</h2>
 *
 * <p>Neither has a {@code default}. A state added to {@link OperationStatus} stops this class
 * compiling until it has a wire value, and a wire value added to the proto stops it compiling until
 * it has a meaning - so the two vocabularies cannot drift by omission. The generated enum's
 * {@code UNRECOGNIZED} and the proto3 zero value are named and rejected rather than defaulted to
 * {@code PENDING}: an operation whose status nobody set is not an operation that is waiting.
 */
public final class OperationMessages {

    private OperationMessages() {
    }

    /**
     * Renders an envelope.
     *
     * @param response   the operation, as the owning module describes it
     * @param retryAfter how long a caller should wait before polling again; {@code null} for a
     *                   terminal operation, which has nothing to wait for
     * @return the wire message
     */
    public static Operation toMessage(OperationResponse response, Duration retryAfter) {
        Operation.Builder message = Operation.newBuilder()
                .setId(response.id())
                .setStatus(toWire(response.status()));
        if (response.detail() != null) {
            message.setDetail(response.detail());
        }
        if (response.progress() != null) {
            message.setProgress(progressMessage(response.progress()));
        }
        if (response.submittedAt() != null) {
            message.setSubmittedAt(timestamp(response.submittedAt()));
        }
        if (response.startedAt() != null) {
            message.setStartedAt(timestamp(response.startedAt()));
        }
        if (response.finishedAt() != null) {
            message.setFinishedAt(timestamp(response.finishedAt()));
        }
        if (response.result() != null) {
            message.setResult(resultMessage(response.result()));
        }
        if (response.failure() != null) {
            message.setFailure(failureMessage(response.failure()));
        }
        if (response.correlationId() != null) {
            message.setCorrelationId(response.correlationId());
        }
        if (retryAfter != null && !retryAfter.isNegative()) {
            message.setRetryAfter(com.google.protobuf.Duration.newBuilder()
                    .setSeconds(retryAfter.getSeconds())
                    .setNanos(retryAfter.getNano()));
        }
        return message.build();
    }

    /**
     * Reads a wire message back into the envelope.
     *
     * @param message an operation received from a platform service
     * @return the envelope
     * @throws IllegalArgumentException when the message has no id or no recognisable status
     */
    public static OperationResponse fromMessage(Operation message) {
        return OperationResponse.builder()
                .id(message.getId())
                .status(fromWire(message.getStatus()))
                .detail(emptyToNull(message.getDetail()))
                .progress(message.hasProgress() ? progressOf(message.getProgress()) : null)
                .submittedAt(message.hasSubmittedAt() ? instant(message.getSubmittedAt()) : null)
                .startedAt(message.hasStartedAt() ? instant(message.getStartedAt()) : null)
                .finishedAt(message.hasFinishedAt() ? instant(message.getFinishedAt()) : null)
                .result(message.hasResult() ? resultOf(message.getResult()) : null)
                .failure(message.hasFailure() ? failureOf(message.getFailure()) : null)
                .correlationId(emptyToNull(message.getCorrelationId()))
                .build();
    }

    /**
     * The retry-after a message carries.
     *
     * @param message an operation received from a platform service
     * @return the delay, or {@code null} when the message states none
     */
    public static Duration retryAfter(Operation message) {
        if (!message.hasRetryAfter()) {
            return null;
        }
        return Duration.ofSeconds(message.getRetryAfter().getSeconds(), message.getRetryAfter().getNanos());
    }

    /**
     * The wire value of a status.
     *
     * @param status a platform operation status
     * @return its wire value, never the unspecified one
     */
    public static ru.ludwigandreas.operation.grpc.v1.OperationStatus toWire(OperationStatus status) {
        return switch (status) {
            case PENDING -> ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_PENDING;
            case RUNNING -> ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_RUNNING;
            case SUCCEEDED -> ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_SUCCEEDED;
            case FAILED -> ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_FAILED;
            case CANCELLED -> ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_CANCELLED;
            case EXPIRED -> ru.ludwigandreas.operation.grpc.v1.OperationStatus.OPERATION_STATUS_EXPIRED;
        };
    }

    /**
     * The platform status a wire value means.
     *
     * @param status a wire value
     * @return the platform status
     * @throws IllegalArgumentException for the unspecified value and for one this build does not
     *                                  know, neither of which may be read as {@code PENDING}
     */
    public static OperationStatus fromWire(ru.ludwigandreas.operation.grpc.v1.OperationStatus status) {
        return switch (status) {
            case OPERATION_STATUS_PENDING -> OperationStatus.PENDING;
            case OPERATION_STATUS_RUNNING -> OperationStatus.RUNNING;
            case OPERATION_STATUS_SUCCEEDED -> OperationStatus.SUCCEEDED;
            case OPERATION_STATUS_FAILED -> OperationStatus.FAILED;
            case OPERATION_STATUS_CANCELLED -> OperationStatus.CANCELLED;
            case OPERATION_STATUS_EXPIRED -> OperationStatus.EXPIRED;
            case OPERATION_STATUS_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException(
                    "Operation.status is " + status + "; the sender did not set it or is newer than this reader");
        };
    }

    private static ru.ludwigandreas.operation.grpc.v1.OperationProgress progressMessage(OperationProgress progress) {
        ru.ludwigandreas.operation.grpc.v1.OperationProgress.Builder message =
                ru.ludwigandreas.operation.grpc.v1.OperationProgress.newBuilder()
                        .setCompleted(progress.completed());
        if (progress.total() != null) {
            message.setTotal(progress.total());
        }
        if (progress.unit() != null) {
            message.setUnit(progress.unit());
        }
        if (progress.phase() != null) {
            message.setPhase(progress.phase());
        }
        return message.build();
    }

    private static OperationProgress progressOf(ru.ludwigandreas.operation.grpc.v1.OperationProgress message) {
        return new OperationProgress(message.getCompleted(),
                message.hasTotal() ? message.getTotal() : null,
                emptyToNull(message.getUnit()),
                emptyToNull(message.getPhase()));
    }

    private static ru.ludwigandreas.operation.grpc.v1.OperationResult resultMessage(OperationResult result) {
        ru.ludwigandreas.operation.grpc.v1.OperationResult.Builder message =
                ru.ludwigandreas.operation.grpc.v1.OperationResult.newBuilder().setHref(result.href());
        if (result.mediaType() != null) {
            message.setMediaType(result.mediaType());
        }
        if (result.sizeBytes() != null) {
            message.setSizeBytes(result.sizeBytes());
        }
        for (OperationResult alternative : result.alternatives()) {
            message.addAlternatives(resultMessage(alternative));
        }
        return message.build();
    }

    private static OperationResult resultOf(ru.ludwigandreas.operation.grpc.v1.OperationResult message) {
        List<OperationResult> alternatives = message.getAlternativesList().stream()
                .map(OperationMessages::resultOf)
                .toList();
        return new OperationResult(message.getHref(),
                emptyToNull(message.getMediaType()),
                message.hasSizeBytes() ? message.getSizeBytes() : null,
                alternatives);
    }

    private static ru.ludwigandreas.operation.grpc.v1.OperationFailure failureMessage(OperationFailure failure) {
        ru.ludwigandreas.operation.grpc.v1.OperationFailure.Builder message =
                ru.ludwigandreas.operation.grpc.v1.OperationFailure.newBuilder().setCode(failure.code());
        if (failure.detail() != null) {
            message.setDetail(failure.detail());
        }
        message.addAllArgs(failure.args());
        return message.build();
    }

    private static OperationFailure failureOf(ru.ludwigandreas.operation.grpc.v1.OperationFailure message) {
        return new OperationFailure(message.getCode(), emptyToNull(message.getDetail()),
                List.copyOf(message.getArgsList()));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
