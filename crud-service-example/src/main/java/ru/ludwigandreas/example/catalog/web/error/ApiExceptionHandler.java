package ru.ludwigandreas.example.catalog.web.error;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.ludwigandreas.db.core.exception.EntityNotFoundException;
import ru.ludwigandreas.example.catalog.support.i18n.LocalizedException;
import ru.ludwigandreas.example.catalog.support.i18n.ProblemStatus;
import ru.ludwigandreas.odatafilter.exception.FilterAccessDeniedException;
import ru.ludwigandreas.odatafilter.exception.ODataFilterException;

/**
 * Turns every failure into an RFC 7807 {@code ProblemDetail} whose title and detail are resolved
 * from the message bundles in the caller's locale.
 *
 * <p>This is also why {@code odata.filter.web.problem-detail-advice-enabled} is switched off in
 * {@code application.yml}: the starter ships its own advice for filter errors, but its messages are
 * English-only, so those exceptions are handled here instead and every response this service emits
 * - validation, business, infrastructure - comes back in one localized shape.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_TYPE_PREFIX = "urn:catalog:error:";

    private final MessageSource messages;

    /** Business failures: the exception itself decides both its code and its outcome. */
    @ExceptionHandler(LocalizedException.class)
    public ProblemDetail handleLocalized(LocalizedException e) {
        log.debug("Rejecting request: {}", e.getMessage());
        return problem(statusOf(e.getStatus()), e.getCode(), e.getArgs());
    }

    /**
     * A rejected OData query. The starter's exceptions carry an English, developer-oriented message;
     * it is used as the fallback only when no translation exists for that exception type.
     */
    @ExceptionHandler(ODataFilterException.class)
    public ProblemDetail handleFilter(ODataFilterException e) {
        HttpStatus status = e instanceof FilterAccessDeniedException
                ? HttpStatus.FORBIDDEN
                : HttpStatus.BAD_REQUEST;
        log.debug("Rejecting OData query: {}", e.getMessage());

        Locale locale = LocaleContextHolder.getLocale();
        String code = "error.filter." + e.getClass().getSimpleName();
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + code));
        problem.setTitle(messages.getMessage("error.filter.title", null, "Invalid query", locale));
        problem.setDetail(messages.getMessage(code, null, e.getMessage(), locale));
        problem.setProperty("code", code);
        return problem;
    }

    /**
     * The caller is authenticated but not entitled - a missing role, or a product outside their data
     * scope.
     *
     * <p>Both cases answer identically and say nothing about which. Distinguishing "you lack the editor
     * role" from "that product belongs to another partner" would turn this endpoint into an oracle for
     * enumerating which products exist, and the detail is already in the module's audit log, keyed by
     * subject, where support can find it.
     *
     * <p>This advice exists because {@code @PreAuthorize} and the data guard throw inside the MVC
     * dispatch, after the security filter chain has handed the request over - so the module's own
     * {@code AccessDeniedHandler} never sees them, and without this they would surface as a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return problem(HttpStatus.FORBIDDEN, "error.forbidden", new Object[0]);
    }

    /** An invalid or absent credential that reached a controller rather than the filter chain. */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException e) {
        log.debug("Rejecting unauthenticated request: {}", e.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "error.unauthorized", new Object[0]);
    }

    /**
     * db-core raises this from {@code getByIdOrThrow}. The service layer normally converts a missing
     * row into its own localized exception first; this is the safety net for anything that doesn't.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFound(EntityNotFoundException e) {
        log.debug("Entity not found: {}", e.getMessage());
        return problem(HttpStatus.NOT_FOUND, "error.not-found", new Object[0]);
    }

    /**
     * Two concurrent writers reached the flush at the same time - the loser gets the same 409 as a
     * client that sent a stale version.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLocking(OptimisticLockingFailureException e) {
        log.warn("Concurrent modification rejected", e);
        return problem(HttpStatus.CONFLICT, "error.concurrent-modification", new Object[0]);
    }

    /**
     * A unique/foreign-key constraint fired. Includes the concurrent-duplicate case the outbox's
     * idempotency index guards against, which is a conflict rather than a server fault.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return problem(HttpStatus.CONFLICT, "error.conflict", new Object[0]);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal", new Object[0]);
    }

    /**
     * Bean Validation failures. The per-field messages are already localized by the validator (it is
     * wired to the same {@code MessageSource}); they are returned as a machine-readable list rather
     * than concatenated into the detail string.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> violations = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", localizedFieldMessage(fieldError)))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "error.validation", new Object[0]);
        problem.setProperty("violations", violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    private String localizedFieldMessage(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        return message == null ? "invalid" : message;
    }

    private ProblemDetail problem(HttpStatus status, String code, Object[] args) {
        Locale locale = LocaleContextHolder.getLocale();
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + code));
        problem.setTitle(messages.getMessage(code + ".title", null, status.getReasonPhrase(), locale));
        problem.setDetail(messages.getMessage(code, args, code, locale));
        problem.setProperty("code", code);
        return problem;
    }

    private HttpStatus statusOf(ProblemStatus status) {
        return switch (status) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
    }
}
