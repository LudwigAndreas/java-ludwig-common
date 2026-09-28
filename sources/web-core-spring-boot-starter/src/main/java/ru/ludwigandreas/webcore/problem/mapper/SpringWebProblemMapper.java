package ru.ludwigandreas.webcore.problem.mapper;

import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Gives Spring MVC's own exceptions localized text under this starter's codes.
 *
 * <p>Without it, the framework failures - a wrong method, an unparseable body, a missing parameter -
 * are the one category of error a service answers in English while everything else is translated,
 * because {@code ResponseEntityExceptionHandler} renders them from the exception's own message. They
 * are also the errors a client is most likely to hit first, while integrating.
 *
 * <p>Deliberately a mapper and not a set of {@code @ExceptionHandler} overrides: it means an
 * application can re-map any one of these by registering its own mapper at a lower order, and it
 * keeps the advice free of a per-exception method list that has to grow with every Spring release.
 *
 * <p>The last clause is the reason nothing falls through to a 500 by accident: any exception that
 * implements {@link ErrorResponse} - which is how Spring 6 models every exception that already knows
 * its own status, including {@link ResponseStatusException} and anything an application derives from
 * it - keeps that status and gets the generic code for it.
 */
public class SpringWebProblemMapper implements ExceptionProblemMapper {

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof HttpRequestMethodNotSupportedException
                || exception instanceof HttpMediaTypeNotSupportedException
                || exception instanceof HttpMediaTypeNotAcceptableException
                || exception instanceof HttpMessageNotReadableException
                || exception instanceof ServletRequestBindingException
                || exception instanceof TypeMismatchException
                || exception instanceof MultipartException
                || exception instanceof AsyncRequestTimeoutException
                || exception instanceof ConversionNotSupportedException
                || exception instanceof HttpMessageNotWritableException
                || exception instanceof ErrorResponse;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        if (exception instanceof HttpRequestMethodNotSupportedException e) {
            return ProblemDefinition.of(
                            ProblemStatus.METHOD_NOT_ALLOWED, ProblemCodes.METHOD_NOT_ALLOWED, e.getMethod())
                    .withProperty("method", e.getMethod())
                    .withProperty("supportedMethods", e.getSupportedMethods());
        }
        if (exception instanceof HttpMediaTypeNotSupportedException e) {
            return ProblemDefinition.of(
                            ProblemStatus.UNSUPPORTED_MEDIA_TYPE,
                            ProblemCodes.UNSUPPORTED_MEDIA_TYPE,
                            String.valueOf(e.getContentType()))
                    .withProperty("supportedMediaTypes", e.getSupportedMediaTypes().stream()
                            .map(Object::toString)
                            .toList());
        }
        if (exception instanceof HttpMediaTypeNotAcceptableException e) {
            return ProblemDefinition.of(ProblemStatus.NOT_ACCEPTABLE, ProblemCodes.NOT_ACCEPTABLE)
                    .withProperty("supportedMediaTypes", e.getSupportedMediaTypes().stream()
                            .map(Object::toString)
                            .toList());
        }
        if (exception instanceof HttpMessageNotReadableException) {
            // The parser's message names a line and column of the payload, and is not translated.
            // It is left out entirely: a client that sent unparseable JSON needs to know that much
            // and can reproduce the rest locally.
            return ProblemDefinition.of(ProblemStatus.INVALID, ProblemCodes.MALFORMED_REQUEST);
        }
        if (exception instanceof MaxUploadSizeExceededException e) {
            return ProblemDefinition.of(
                            ProblemStatus.PAYLOAD_TOO_LARGE,
                            ProblemCodes.PAYLOAD_TOO_LARGE,
                            e.getMaxUploadSize())
                    .withProperty("maxUploadSize", e.getMaxUploadSize());
        }
        if (exception instanceof MultipartException) {
            return ProblemDefinition.of(ProblemStatus.INVALID, ProblemCodes.MALFORMED_REQUEST);
        }
        if (exception instanceof TypeMismatchException e) {
            // Covers MethodArgumentTypeMismatchException: a path variable or parameter that could not
            // be converted. The name is machine-readable; the offending value is deliberately not
            // echoed back - see the include-rejected-value note on the properties class.
            return badRequest(e.getPropertyName());
        }
        if (exception instanceof MissingServletRequestParameterException e) {
            return badRequest(e.getParameterName());
        }
        if (exception instanceof MissingRequestHeaderException e) {
            return badRequest(e.getHeaderName());
        }
        if (exception instanceof MissingServletRequestPartException e) {
            return badRequest(e.getRequestPartName());
        }
        if (exception instanceof ServletRequestBindingException) {
            // Any other binding failure: a missing cookie, a missing matrix variable.
            return badRequest(null);
        }
        if (exception instanceof AsyncRequestTimeoutException) {
            return ProblemDefinition.of(ProblemStatus.GATEWAY_TIMEOUT, ProblemCodes.UPSTREAM_UNAVAILABLE);
        }
        if (exception instanceof ConversionNotSupportedException
                || exception instanceof HttpMessageNotWritableException) {
            // Both are this service's own serialization bugs, not the caller's problem.
            return ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL);
        }
        ProblemStatus status = ProblemStatus.ofCode(((ErrorResponse) exception).getStatusCode().value());
        return ProblemDefinition.of(status, ProblemCodes.forStatus(status));
    }

    /**
     * A 400 naming the offending input in a {@code parameter} member rather than in the sentence.
     *
     * <p>The message itself takes no arguments, so it stays a complete sentence in every language
     * whether or not the name is known - a translator should not have to make one string read well
     * both with and without an interpolated identifier.
     */
    private ProblemDefinition badRequest(String parameter) {
        ProblemDefinition problem = ProblemDefinition.of(ProblemStatus.INVALID, ProblemCodes.BAD_REQUEST);
        return parameter == null || parameter.isBlank()
                ? problem
                : problem.withProperty("parameter", parameter);
    }

    @Override
    public int getOrder() {
        // Late: a service re-mapping one of these - a 404 for an unknown path rendered as its own
        // code, say - registers at the default order and wins without naming a number.
        return DEFAULT_MODULE_ORDER;
    }
}
