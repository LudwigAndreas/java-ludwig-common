package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Turns an exception into the status a call is closed with - through the same
 * {@link ProblemMapperRegistry} and {@link ProblemDetailFactory} the HTTP advice uses.
 *
 * <p>There is no gRPC-specific mapper to write: a module contributes an
 * {@code ExceptionProblemMapper} once and both transports render it. An exception no mapper claims
 * becomes {@code INTERNAL} carrying the generic internal-error text and the correlation id, and
 * never the exception's own message, which is the pipeline's existing no-leak rule.
 */
@Slf4j
public class GrpcProblemRenderer {

    private static final int FIRST_SERVER_ERROR = 500;

    private final ProblemMapperRegistry registry;
    private final ProblemDetailFactory problems;
    private final GrpcStatusCodec codec;

    /**
     * Creates the renderer.
     *
     * @param registry the platform's problem mappers
     * @param problems the platform's problem factory
     * @param codec    the status codec, carrying this service's name as the error domain
     */
    public GrpcProblemRenderer(ProblemMapperRegistry registry, ProblemDetailFactory problems,
                               GrpcStatusCodec codec) {
        this.registry = registry;
        this.problems = problems;
        this.codec = codec;
    }

    /**
     * Renders a failure. Reads the locale and the correlation id from the current thread and
     * context, so it must run inside the call's bindings.
     *
     * @param failure        what the handler or an interceptor threw
     * @param fullMethodName the method being served
     * @return the exception to close the call with; never throws
     */
    public StatusRuntimeException render(Throwable failure, String fullMethodName) {
        return render(failure, fullMethodName, null);
    }

    /**
     * Renders a failure that tells the caller when to come back.
     *
     * @param failure        what the handler or an interceptor threw
     * @param fullMethodName the method being served
     * @param retryAfter     how long the caller should wait, or {@code null}
     * @return the exception to close the call with; never throws
     */
    public StatusRuntimeException render(Throwable failure, String fullMethodName, Duration retryAfter) {
        // A handler that chose its own status meant it.
        if (failure instanceof StatusRuntimeException chosen) {
            return chosen;
        }
        if (failure instanceof StatusException chosen) {
            return chosen.getStatus().asRuntimeException(chosen.getTrailers());
        }
        // CHECKSTYLE.OFF: IllegalCatch - a failure while rendering a failure must still close the call
        try {
            Optional<ProblemDefinition> mapped = registry.resolve(failure);
            ProblemDefinition definition = mapped
                    .orElseGet(() -> ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL));
            if (definition.status().code() >= FIRST_SERVER_ERROR) {
                log.error("gRPC method {} failed", fullMethodName, failure);
            } else {
                log.debug("gRPC method {} answered {}: {}", fullMethodName, definition.code(), failure.toString());
            }
            ProblemDetail problem = problems.create(definition, "/" + fullMethodName);
            return codec.toException(problem, LocaleContextHolder.getLocale(),
                    GrpcCallContext.CORRELATION_ID.get(), retryAfter);
        } catch (RuntimeException renderingFailed) {
            log.error("Could not render the failure of gRPC method {}", fullMethodName, renderingFailed);
            return Status.INTERNAL.asRuntimeException();
        }
        // CHECKSTYLE.ON: IllegalCatch
    }
}
