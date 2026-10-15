package ru.ludwigandreas.grpc.core.problem;

import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.webcore.problem.Violation;

/**
 * A gRPC failure read back into the platform's vocabulary.
 *
 * @param code          the gRPC status code the peer answered with
 * @param problem       the problem document, rebuilt from the status details when the peer is a
 *                      platform server and synthesized from the code alone when it is not
 * @param violations    the field violations the peer reported, empty when there were none
 * @param retryAfter    how long the peer asked the caller to wait, when it said
 * @param correlationId the correlation id the peer rendered the failure under, when it sent one
 * @param fromPlatform  whether the status carried the platform's own error details. {@code false}
 *                      means {@code problem} is an approximation and its code is a generic one
 */
public record DecodedProblem(
        Status.Code code,
        ProblemDetail problem,
        List<Violation> violations,
        Optional<Duration> retryAfter,
        Optional<String> correlationId,
        boolean fromPlatform) {

    /**
     * Normalises the collections so that a decoded problem is safe to hold and to compare.
     */
    public DecodedProblem {
        violations = violations == null ? List.of() : List.copyOf(violations);
        retryAfter = retryAfter == null ? Optional.empty() : retryAfter;
        correlationId = correlationId == null ? Optional.empty() : correlationId;
    }

    /**
     * The problem code - the stable, machine-readable name a caller branches on.
     *
     * @return the code, or empty when the peer attached none
     */
    public Optional<String> problemCode() {
        Object value = problem.getProperties() == null ? null : problem.getProperties().get(GrpcStatusCodec.CODE);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }
}
