package ru.ludwigandreas.grpc.core.problem;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.LocalizedMessage;
import com.google.rpc.RetryInfo;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.webcore.problem.Violation;

/**
 * Renders an RFC 9457 problem as a {@code google.rpc.Status}, and reads one back.
 *
 * <h2>This is a rendering, not a mapping</h2>
 *
 * <p>Deciding <em>which</em> problem an exception is stays where it has always been: in
 * {@code web-core}'s {@code ProblemMapperRegistry}, fed by the mappers every module contributes.
 * This class starts from the {@link ProblemDetail} that pipeline produced and changes only the
 * envelope. A module therefore never writes a gRPC-specific exception mapping; the mapper it
 * contributed for HTTP is the one that takes effect here.
 *
 * <h2>What travels, and where</h2>
 *
 * <table>
 *   <caption>Problem members and their place in the status</caption>
 *   <tr><th>Problem</th><th>Status</th></tr>
 *   <tr><td>status</td><td>the gRPC code (see {@link GrpcStatusCodes}), and the exact number in
 *       {@code ErrorInfo.metadata[http_status]}</td></tr>
 *   <tr><td>{@code code} property</td><td>{@code ErrorInfo.reason}</td></tr>
 *   <tr><td>type, title</td><td>{@code ErrorInfo.metadata[type]}, {@code [title]}</td></tr>
 *   <tr><td>detail</td><td>the status message, and {@code LocalizedMessage} with its locale</td></tr>
 *   <tr><td>{@code violations} property</td><td>{@code BadRequest.field_violations}</td></tr>
 *   <tr><td>a retry-after</td><td>{@code RetryInfo.retry_delay}</td></tr>
 *   <tr><td>the correlation id</td><td>{@code ErrorInfo.metadata[correlation_id]}</td></tr>
 * </table>
 *
 * <p>Nothing else is copied. In particular the problem's free-form properties are <b>not</b>
 * forwarded wholesale: a property is a module's own extension member, some of them are large, and
 * metadata is bounded. A caller that needs one reads the problem code and asks for the resource.
 *
 * <h2>What never travels</h2>
 *
 * <p>An exception message, a class name, a stack trace. This class has no parameter of type
 * {@code Throwable} on the encoding side on purpose: it cannot leak what it is never given. The
 * {@code debug} property {@code web-core} can add to a 5xx is dropped here even when configured,
 * because the peer is another service's log, not a developer's browser.
 */
public final class GrpcStatusCodec {

    /**
     * The name of the problem property that carries the problem code.
     */
    public static final String CODE = "code";

    /**
     * The name of the problem property that carries field violations.
     */
    public static final String VIOLATIONS = "violations";

    static final String META_HTTP_STATUS = "http_status";
    static final String META_TYPE = "type";
    static final String META_TITLE = "title";
    static final String META_CORRELATION_ID = "correlation_id";

    private static final String FIELD = "field";
    private static final String MESSAGE = "message";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final String domain;

    /**
     * Creates a codec.
     *
     * @param domain what {@code ErrorInfo.domain} says produced the failure - the service name
     */
    public GrpcStatusCodec(String domain) {
        this.domain = domain == null || domain.isBlank() ? "ludwig" : domain;
    }

    /**
     * Renders a problem.
     *
     * @param problem       the problem document the platform's pipeline produced
     * @param locale        the locale {@code problem}'s detail is written in, or {@code null}
     * @param correlationId the correlation id of the failing call, or {@code null}
     * @param retryAfter    how long the caller should wait before repeating, or {@code null}
     * @return the status, ready for {@link StatusProto#toStatusRuntimeException}
     */
    public com.google.rpc.Status encode(ProblemDetail problem, Locale locale, String correlationId,
                                        Duration retryAfter) {
        ProblemStatus problemStatus = ProblemStatus.ofCode(problem.getStatus());
        Status.Code code = GrpcStatusCodes.forProblemStatus(problemStatus);
        String problemCode = stringProperty(problem, CODE);

        ErrorInfo.Builder info = ErrorInfo.newBuilder()
                .setDomain(domain)
                .setReason(problemCode == null ? ProblemCodes.forStatus(problemStatus) : problemCode)
                .putMetadata(META_HTTP_STATUS, Integer.toString(problem.getStatus()));
        if (problem.getType() != null) {
            info.putMetadata(META_TYPE, problem.getType().toString());
        }
        if (problem.getTitle() != null) {
            info.putMetadata(META_TITLE, problem.getTitle());
        }
        if (correlationId != null && !correlationId.isBlank()) {
            info.putMetadata(META_CORRELATION_ID, correlationId);
        }

        com.google.rpc.Status.Builder status = com.google.rpc.Status.newBuilder()
                .setCode(code.value())
                .setMessage(problem.getDetail() == null ? "" : problem.getDetail())
                .addDetails(Any.pack(info.build()));

        if (problem.getDetail() != null && locale != null) {
            status.addDetails(Any.pack(LocalizedMessage.newBuilder()
                    .setLocale(locale.toLanguageTag())
                    .setMessage(problem.getDetail())
                    .build()));
        }
        BadRequest violations = violations(problem);
        if (violations.getFieldViolationsCount() > 0) {
            status.addDetails(Any.pack(violations));
        }
        if (retryAfter != null && !retryAfter.isNegative()) {
            status.addDetails(Any.pack(RetryInfo.newBuilder()
                    .setRetryDelay(com.google.protobuf.Duration.newBuilder()
                            .setSeconds(retryAfter.getSeconds())
                            .setNanos(retryAfter.getNano()))
                    .build()));
        }
        return status.build();
    }

    /**
     * Renders a problem as the exception a server call is closed with.
     *
     * @param problem       the problem document
     * @param locale        the locale of its detail, or {@code null}
     * @param correlationId the correlation id, or {@code null}
     * @param retryAfter    a retry-after, or {@code null}
     * @return an exception whose trailers carry the status details
     */
    public StatusRuntimeException toException(ProblemDetail problem, Locale locale, String correlationId,
                                              Duration retryAfter) {
        return StatusProto.toStatusRuntimeException(encode(problem, locale, correlationId, retryAfter));
    }

    /**
     * Reads a failure back.
     *
     * <p>Never throws on a malformed or foreign status: a failure that cannot be decoded is still
     * a failure the caller has to handle, and an exception from the decoder would replace the
     * peer's answer with a parsing error.
     *
     * @param failure the {@code StatusRuntimeException} or {@code StatusException} a call failed
     *                with, or anything wrapping one
     * @return the decoded failure, or empty when {@code failure} carries no gRPC status at all
     */
    public Optional<DecodedProblem> decode(Throwable failure) {
        if (!carriesStatus(failure)) {
            return Optional.empty();
        }
        return Optional.of(read(Status.fromThrowable(failure), StatusProto.fromThrowable(failure)));
    }

    /**
     * Whether a gRPC status is anywhere in the cause chain.
     *
     * <p>{@link Status#fromThrowable} answers {@code UNKNOWN} for any exception at all, so it cannot
     * be asked this question: an {@code IllegalStateException} thrown by the caller's own code would
     * come back looking like the peer had answered.
     */
    private static boolean carriesStatus(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StatusRuntimeException || current instanceof StatusException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private DecodedProblem read(Status grpcStatus, com.google.rpc.Status proto) {
        ErrorInfo info = detail(proto, ErrorInfo.class);
        LocalizedMessage localized = detail(proto, LocalizedMessage.class);
        RetryInfo retry = detail(proto, RetryInfo.class);

        Status.Code code = grpcStatus.getCode();
        boolean fromPlatform = info != null && info.containsMetadata(META_HTTP_STATUS);
        ProblemStatus problemStatus = fromPlatform
                ? httpStatus(info.getMetadataOrDefault(META_HTTP_STATUS, ""), code)
                : GrpcStatusCodes.toProblemStatus(code);

        ProblemDetail problem = ProblemDetail.forStatus(problemStatus.code());
        String detail = localized != null ? localized.getMessage() : grpcStatus.getDescription();
        if (detail != null && !detail.isEmpty()) {
            problem.setDetail(detail);
        }
        describe(problem, info, problemStatus);
        List<Violation> violations = readViolations(problem, detail(proto, BadRequest.class));

        Optional<Duration> retryAfter = retry == null || !retry.hasRetryDelay()
                ? Optional.empty()
                : Optional.of(Duration.ofSeconds(retry.getRetryDelay().getSeconds(),
                        Math.floorMod(retry.getRetryDelay().getNanos(), NANOS_PER_SECOND)));
        Optional<String> correlationId = info == null
                ? Optional.empty()
                : Optional.ofNullable(info.getMetadataMap().get(META_CORRELATION_ID));
        return new DecodedProblem(code, problem, violations, retryAfter, correlationId, fromPlatform);
    }

    /**
     * The first detail of {@code type}, or {@code null}.
     *
     * <p>A detail that does not parse is skipped rather than fatal - see {@link #decode(Throwable)}.
     * The remaining details and the code are still the peer's answer.
     */
    private static <T extends Message> T detail(com.google.rpc.Status proto, Class<T> type) {
        if (proto == null) {
            return null;
        }
        for (Any candidate : proto.getDetailsList()) {
            if (candidate.is(type)) {
                try {
                    return candidate.unpack(type);
                } catch (InvalidProtocolBufferException malformed) {
                    continue;
                }
            }
        }
        return null;
    }

    private static void describe(ProblemDetail problem, ErrorInfo info, ProblemStatus problemStatus) {
        if (info == null) {
            problem.setProperty(CODE, ProblemCodes.forStatus(problemStatus));
            return;
        }
        if (!info.getReason().isEmpty()) {
            problem.setProperty(CODE, info.getReason());
        }
        uri(info.getMetadataOrDefault(META_TYPE, "")).ifPresent(problem::setType);
        String title = info.getMetadataOrDefault(META_TITLE, "");
        if (!title.isEmpty()) {
            problem.setTitle(title);
        }
    }

    private static List<Violation> readViolations(ProblemDetail problem, BadRequest badRequest) {
        if (badRequest == null || badRequest.getFieldViolationsCount() == 0) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (BadRequest.FieldViolation violation : badRequest.getFieldViolationsList()) {
            violations.add(Violation.of(violation.getField(), violation.getDescription(), null));
            rendered.add(Map.of(FIELD, violation.getField(), MESSAGE, violation.getDescription()));
        }
        problem.setProperty(VIOLATIONS, rendered);
        return violations;
    }

    private static ProblemStatus httpStatus(String value, Status.Code fallback) {
        try {
            return ProblemStatus.ofCode(Integer.parseInt(value));
        } catch (NumberFormatException notANumber) {
            return GrpcStatusCodes.toProblemStatus(fallback);
        }
    }

    private static Optional<URI> uri(String value) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(value));
        } catch (URISyntaxException notAUri) {
            return Optional.empty();
        }
    }

    private static BadRequest violations(ProblemDetail problem) {
        BadRequest.Builder builder = BadRequest.newBuilder();
        Object value = problem.getProperties() == null ? null : problem.getProperties().get(VIOLATIONS);
        if (value instanceof Collection<?> entries) {
            for (Object entry : entries) {
                // web-core renders a Violation to a map before it reaches a problem, applying its
                // include-rejected-value policy as it does. Reading the rendered form - and only
                // its field and message - is what keeps that policy in force here: a rejected
                // value the HTTP response would have withheld is not in the map to be copied.
                if (entry instanceof Map<?, ?> map && map.get(FIELD) != null) {
                    Object message = map.get(MESSAGE);
                    builder.addFieldViolations(BadRequest.FieldViolation.newBuilder()
                            .setField(map.get(FIELD).toString())
                            .setDescription(message == null ? "" : message.toString()));
                } else if (entry instanceof Violation violation && violation.field() != null) {
                    builder.addFieldViolations(BadRequest.FieldViolation.newBuilder()
                            .setField(violation.field())
                            .setDescription(violation.message() == null ? "" : violation.message()));
                }
            }
        }
        return builder.build();
    }

    private static String stringProperty(ProblemDetail problem, String name) {
        Object value = problem.getProperties() == null ? null : problem.getProperties().get(name);
        return value == null ? null : value.toString();
    }
}
