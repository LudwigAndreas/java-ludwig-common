package ru.ludwigandreas.odatafilter.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.ludwigandreas.odatafilter.exception.FilterAccessDeniedException;
import ru.ludwigandreas.odatafilter.exception.ODataFilterException;

/**
 * Maps this library's exceptions to RFC 7807 {@link ProblemDetail} responses. Registered at the
 * lowest precedence so an application's own {@code @ExceptionHandler} for the same exception
 * type, if any, wins. Disable entirely with {@code odata.filter.web.problem-detail-advice-enabled=false}
 * to handle these exceptions yourself.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ODataFilterExceptionHandler {

    @ExceptionHandler(FilterAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleAccessDenied(FilterAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Filter access denied");
        problem.setProperty("property", ex.propertyPath());
        return problem;
    }

    @ExceptionHandler(ODataFilterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleInvalidFilter(ODataFilterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid OData query");
        return problem;
    }
}
