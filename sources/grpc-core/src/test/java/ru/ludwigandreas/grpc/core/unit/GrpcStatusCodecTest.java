package ru.ludwigandreas.grpc.core.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.grpc.core.problem.DecodedProblem;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodes;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

class GrpcStatusCodecTest {

    private final GrpcStatusCodec codec = new GrpcStatusCodec("catalog");

    @ParameterizedTest
    @EnumSource(ProblemStatus.class)
    @DisplayName("every problem status the platform declares has a gRPC code that says something")
    void tableIsTotal(ProblemStatus status) {
        Status.Code code = GrpcStatusCodes.forProblemStatus(status);

        assertThat(code).isNotNull().isNotIn(Status.Code.OK, Status.Code.UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, mode = EnumSource.Mode.EXCLUDE, names = "OK")
    @DisplayName("every failure code has a fallback problem status")
    void reverseTableIsTotal(Status.Code code) {
        assertThat(GrpcStatusCodes.toProblemStatus(code)).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(ProblemStatus.class)
    @DisplayName("a problem survives the wire with its exact status, not just its gRPC code")
    void statusSurvivesRoundTrip(ProblemStatus status) {
        ProblemDetail problem = ProblemDetail.forStatus(status.code());
        problem.setProperty("code", "catalog.error.sample");

        DecodedProblem decoded = roundTrip(problem, null, null);

        assertThat(decoded.problem().getStatus()).isEqualTo(status.code());
        assertThat(decoded.code()).isEqualTo(GrpcStatusCodes.forProblemStatus(status));
        assertThat(decoded.fromPlatform()).isTrue();
    }

    @Test
    @DisplayName("code, type, title, detail, correlation id and retry-after all survive")
    void membersSurviveRoundTrip() {
        ProblemDetail problem = ProblemDetail.forStatus(404);
        problem.setType(URI.create("urn:ludwig:problem:error.product.not-found"));
        problem.setTitle("Not Found");
        problem.setDetail("Product 42 does not exist");
        problem.setProperty("code", "error.product.not-found");

        com.google.rpc.Status encoded =
                codec.encode(problem, Locale.forLanguageTag("en-GB"), "corr-1", Duration.ofMillis(2500));
        DecodedProblem decoded = codec.decode(io.grpc.protobuf.StatusProto.toStatusRuntimeException(encoded))
                .orElseThrow();

        assertThat(encoded.getCode()).isEqualTo(Status.Code.NOT_FOUND.value());
        assertThat(errorInfo(encoded).getReason()).isEqualTo("error.product.not-found");
        assertThat(errorInfo(encoded).getDomain()).isEqualTo("catalog");
        assertThat(errorInfo(encoded).getMetadataMap()).containsEntry("http_status", "404");

        assertThat(decoded.problemCode()).contains("error.product.not-found");
        assertThat(decoded.problem().getType()).hasToString("urn:ludwig:problem:error.product.not-found");
        assertThat(decoded.problem().getTitle()).isEqualTo("Not Found");
        assertThat(decoded.problem().getDetail()).isEqualTo("Product 42 does not exist");
        assertThat(decoded.correlationId()).contains("corr-1");
        assertThat(decoded.retryAfter()).contains(Duration.ofMillis(2500));
    }

    @Test
    @DisplayName("violations travel as field violations, without the rejected value")
    void violationsSurviveRoundTrip() {
        ProblemDetail problem = ProblemDetail.forStatus(400);
        problem.setProperty("code", ProblemCodes.VALIDATION);
        // The shape web-core renders a Violation to, with a rejected value an HTTP response would
        // only carry if include-rejected-value were on. It must not cross the wire regardless.
        problem.setProperty("violations", List.of(
                Map.of("field", "name", "message", "must not be blank", "rejectedValue", "hunter2"),
                Map.of("field", "price", "message", "must be positive")));

        com.google.rpc.Status encoded = codec.encode(problem, null, null, null);
        DecodedProblem decoded = codec.decode(io.grpc.protobuf.StatusProto.toStatusRuntimeException(encoded))
                .orElseThrow();

        assertThat(encoded.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT.value());
        assertThat(encoded.toString()).doesNotContain("hunter2");
        assertThat(decoded.violations()).extracting("field").containsExactly("name", "price");
        assertThat(decoded.violations()).extracting("message")
                .containsExactly("must not be blank", "must be positive");
        assertThat(decoded.problem().getProperties()).containsKey("violations");
    }

    @Test
    @DisplayName("free-form problem properties, including a debug member, are not forwarded")
    void propertiesAreNotForwarded() {
        ProblemDetail problem = ProblemDetail.forStatus(500);
        problem.setDetail("The request could not be processed");
        problem.setProperty("code", ProblemCodes.INTERNAL);
        problem.setProperty("debug", "java.lang.IllegalStateException: password=hunter2");

        com.google.rpc.Status encoded = codec.encode(problem, Locale.ENGLISH, "corr-9", null);

        assertThat(encoded.getCode()).isEqualTo(Status.Code.INTERNAL.value());
        assertThat(encoded.toString()).doesNotContain("hunter2").doesNotContain("IllegalStateException");
        assertThat(errorInfo(encoded).getMetadataMap()).containsEntry("correlation_id", "corr-9");
    }

    @Test
    @DisplayName("a failure from a server that is not on this platform decodes to an approximation")
    void foreignStatusDecodes() {
        StatusRuntimeException foreign = Status.UNAVAILABLE.withDescription("upstream connect error")
                .asRuntimeException();

        DecodedProblem decoded = codec.decode(foreign).orElseThrow();

        assertThat(decoded.fromPlatform()).isFalse();
        assertThat(decoded.code()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(decoded.problem().getStatus()).isEqualTo(503);
        assertThat(decoded.problemCode()).contains(ProblemCodes.forStatus(ProblemStatus.SERVICE_UNAVAILABLE));
        assertThat(decoded.retryAfter()).isEmpty();
    }

    @Test
    @DisplayName("a wrapped status is found through the cause chain")
    void wrappedStatusDecodes() {
        RuntimeException wrapped = new IllegalStateException("wrapper",
                Status.NOT_FOUND.withDescription("gone").asRuntimeException());

        assertThat(codec.decode(wrapped)).map(DecodedProblem::code).contains(Status.Code.NOT_FOUND);
    }

    @Test
    @DisplayName("something that is not a gRPC failure at all is not decoded into one")
    void nonGrpcFailureIsEmpty() {
        assertThat(codec.decode(new IllegalStateException("not gRPC"))).isEmpty();
        assertThat(codec.decode(null)).isEmpty();
    }

    @Test
    @DisplayName("a malformed detail is skipped and the rest of the answer is kept")
    void malformedDetailIsSkipped() {
        com.google.rpc.Status broken = com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.ABORTED.value())
                .setMessage("conflict")
                .addDetails(Any.newBuilder()
                        .setTypeUrl("type.googleapis.com/google.rpc.ErrorInfo")
                        .setValue(com.google.protobuf.ByteString.copyFrom(new byte[] {(byte) 0xff, 0x01})))
                .build();

        Optional<DecodedProblem> decoded =
                codec.decode(io.grpc.protobuf.StatusProto.toStatusRuntimeException(broken));

        assertThat(decoded).map(DecodedProblem::code).contains(Status.Code.ABORTED);
        assertThat(decoded.orElseThrow().problem().getStatus()).isEqualTo(409);
    }

    private DecodedProblem roundTrip(ProblemDetail problem, String correlationId, Duration retryAfter) {
        return codec.decode(codec.toException(problem, null, correlationId, retryAfter)).orElseThrow();
    }

    private static ErrorInfo errorInfo(com.google.rpc.Status status) {
        return status.getDetailsList().stream()
                .filter(detail -> detail.is(ErrorInfo.class))
                .findFirst()
                .map(detail -> {
                    try {
                        return detail.unpack(ErrorInfo.class);
                    } catch (com.google.protobuf.InvalidProtocolBufferException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                })
                .orElseThrow();
    }
}
