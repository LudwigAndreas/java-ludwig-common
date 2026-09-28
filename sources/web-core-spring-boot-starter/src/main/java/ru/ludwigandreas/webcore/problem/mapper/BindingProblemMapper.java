package ru.ludwigandreas.webcore.problem.mapper;

import java.util.ArrayList;
import java.util.List;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.webcore.problem.Violation;

/**
 * Renders a rejected request body as one problem with a list of field violations.
 *
 * <p>Covers {@code MethodArgumentNotValidException} - a {@code @Valid @RequestBody} that failed -
 * and any other {@link BindException}.
 *
 * <p>The per-field messages are not concatenated into {@code detail}: a client needs to attach each
 * one to the input that produced it, and a single joined sentence forces it to either re-implement
 * the validation or show the whole string next to every field. They are already localized, because
 * the starter wires Bean Validation to the same {@code MessageSource} the problem text comes from -
 * without that, a request would come back with a translated title and English field messages.
 *
 * <p>Ordered ahead of {@link SpringWebProblemMapper}: {@code MethodArgumentNotValidException} is also
 * an {@code ErrorResponse}, and that mapper's status-derived fallback would answer it as a generic
 * 400 with no violations at all.
 */
public class BindingProblemMapper implements ExceptionProblemMapper {

    /** Ahead of the generic mapper that would otherwise claim these types - the lower order wins. */
    private static final int ORDER = DEFAULT_MODULE_ORDER - 100;

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof BindException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        return ProblemDefinition.of(ProblemStatus.INVALID, ProblemCodes.VALIDATION)
                .withProperty("violations", violations((BindException) exception));
    }

    /**
     * Also used for the nested-object results of method validation, which arrive as plain
     * {@link Errors} rather than as an exception.
     */
    static List<Violation> violations(Errors errors) {
        List<Violation> violations = new ArrayList<>();
        for (FieldError fieldError : errors.getFieldErrors()) {
            violations.add(new Violation(
                    fieldError.getField(),
                    message(fieldError),
                    fieldError.getCode(),
                    fieldError.getRejectedValue()));
        }
        for (ObjectError globalError : errors.getGlobalErrors()) {
            // A class-level constraint (@ScriptAssert, a custom cross-field check) has no single
            // field to blame; it is reported against the object so the client can still display it.
            violations.add(Violation.of(
                    globalError.getObjectName(), message(globalError), globalError.getCode()));
        }
        return violations;
    }

    private static String message(ObjectError error) {
        String message = error.getDefaultMessage();
        return message == null || message.isBlank() ? "invalid" : message;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
