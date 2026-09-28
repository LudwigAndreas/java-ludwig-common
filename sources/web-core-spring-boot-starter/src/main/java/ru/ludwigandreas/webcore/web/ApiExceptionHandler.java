package ru.ludwigandreas.webcore.web;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Turns every failure into an RFC 9457 {@code ProblemDetail} whose title and detail are resolved
 * from the contributed message bundles in the caller's locale.
 *
 * <p>There is deliberately one of these per service, not one per module. The pattern it replaces was
 * each starter shipping its own {@code @RestControllerAdvice} for its own exceptions, and it broke
 * down in two ways that were not obvious until several modules were in one service:
 *
 * <ul>
 *   <li><b>Language.</b> A library advice can only emit the text it was compiled with, so its errors
 *       came back in English while the service's own came back translated. The only fix available to
 *       the application was to switch that advice off and re-handle the library's exceptions itself -
 *       which every service then did, identically, by hand.
 *   <li><b>Shape.</b> Several advices meant several opinions about what a problem document contains.
 *       A client could not rely on {@code code} being present, because whether it was depended on
 *       which subsystem had failed.
 * </ul>
 *
 * <p>So this class knows about no module's exceptions at all. Everything reaches it as a
 * {@link ProblemDefinition} produced by an {@link ru.ludwigandreas.webcore.problem.ExceptionProblemMapper},
 * and a module extends the API's error vocabulary by contributing a mapper and a message bundle -
 * neither of which competes with anything the application or another module registered.
 *
 * <h2>Routing</h2>
 *
 * <p>Two entry points, one funnel:
 *
 * <ul>
 *   <li>{@link #handleExceptionInternal} - every exception Spring MVC itself raises. Overriding this
 *       single method, rather than the twenty-odd per-type callbacks
 *       {@link ResponseEntityExceptionHandler} offers, is what keeps this class from growing with
 *       each Spring release: a new framework exception type is rendered correctly the moment it
 *       exists, using the status Spring already decided, and giving it better text is a bundle key
 *       plus a mapper branch.
 *   <li>{@link #handleUnhandled} - everything else, including the business exceptions.
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler implements Ordered {

    private final ProblemDetailFactory problems;
    private final ProblemMapperRegistry mappers;
    private final int order;

    public ApiExceptionHandler(ProblemDetailFactory problems, ProblemMapperRegistry mappers, int order) {
        this.problems = problems;
        this.mappers = mappers;
        this.order = order;
    }

    /**
     * Anything not raised by Spring MVC: the service's own {@code LocalizedException}s, a module's
     * exceptions, an authorization failure thrown inside the dispatch, and the genuinely unexpected.
     *
     * <p>{@code Exception} rather than {@code Throwable} on purpose: an {@code Error} means the JVM
     * or the application is in a state where the useful thing is to let it propagate, not to spend
     * the remaining heap formatting a polite 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnhandled(Exception exception, WebRequest request) {
        ProblemDefinition definition = mappers.resolve(exception)
                // Nothing claimed it. A 500 whose detail says only "the request could not be
                // processed": the exception's message is written for whoever operates this service,
                // and forwarding it is how a stack frame, a SQL fragment or an internal hostname ends
                // up in a client's log.
                .orElseGet(() -> ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL));
        return respond(definition, exception, HttpHeaders.EMPTY, request);
    }

    /**
     * Every exception {@link ResponseEntityExceptionHandler} handles, re-rendered.
     *
     * <p>The {@code headers} Spring prepared are preserved - that is how the {@code Allow} header
     * survives on a 405, which the RFC requires and which a hand-rolled advice for that exception
     * almost always loses.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDefinition definition = mappers.resolve(exception)
                // Unmapped framework exception: keep the status Spring decided and give it the
                // generic code for that status, so it is still localized and still carries a code.
                .orElseGet(() -> {
                    ProblemStatus problemStatus = ProblemStatus.ofCode(status.value());
                    return ProblemDefinition.of(problemStatus, ProblemCodes.forStatus(problemStatus));
                });
        return respond(definition, exception, headers, request);
    }

    private ResponseEntity<Object> respond(
            ProblemDefinition definition, Exception exception, HttpHeaders headers, WebRequest request) {
        log(definition, exception);

        ProblemDetail problem = problems.create(definition, requestUri(request));
        if (definition.status().isServerError() && problems.config().isIncludeExceptionMessage()) {
            problem.setProperty("debug", exception.toString());
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // Says which language the body actually came back in, which is not always the one that was
        // asked for - a caller requesting an unsupported locale gets the default, and has no other
        // way to find that out.
        Locale locale = LocaleContextHolder.getLocale();
        if (locale != null) {
            responseHeaders.setContentLanguage(locale);
        }
        return ResponseEntity.status(definition.status().code())
                .headers(responseHeaders)
                .body(problem);
    }

    /**
     * One line per failure, at a level that reflects whose fault it is.
     *
     * <p>A 4xx is a normal answer and is logged without a stack trace: a service that logs one for
     * every rejected request produces a log where the 500s are invisible. A 5xx gets the full trace,
     * because that one is ours.
     */
    private void log(ProblemDefinition definition, Exception exception) {
        if (definition.status().isServerError()) {
            log.error("Request failed with {} [{}]", definition.status(), definition.code(), exception);
        } else if (log.isDebugEnabled()) {
            log.debug("Rejecting request with {} [{}]: {}",
                    definition.status(), definition.code(), exception.getMessage());
        }
    }

    private String requestUri(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
