package ru.ludwigandreas.webcore.problem.mapper;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.webcore.problem.Violation;

/**
 * Renders a failed constraint on a controller method's own parameters.
 *
 * <p>This is the other half of validation, and the half that is usually forgotten: a
 * {@code @RequestParam @Min(1) int page} or a {@code @PathVariable @Pattern(...) String code} does
 * not produce a {@code MethodArgumentNotValidException}. Spring 6.1 reports it as a
 * {@code HandlerMethodValidationException} instead, and a service that only handles body validation
 * answers those with a bare 400 whose body says nothing about which parameter was wrong - or, before
 * 6.1 built this in, with a 500.
 *
 * <p>Reported in the same {@code violations} shape as body validation, so a client has one code path
 * for "the request was rejected field by field" regardless of where the fields came from.
 */
public class HandlerMethodValidationProblemMapper implements ExceptionProblemMapper {

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof HandlerMethodValidationException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        HandlerMethodValidationException validation = (HandlerMethodValidationException) exception;
        List<Violation> violations = new ArrayList<>();
        for (ParameterValidationResult result : validation.getAllValidationResults()) {
            if (result instanceof ParameterErrors nested) {
                // A @Valid object argument that is not the body - a validated @ModelAttribute, an
                // element of a validated collection. Its errors are per-field, like a body's.
                violations.addAll(BindingProblemMapper.violations(nested));
                continue;
            }
            String parameter = parameterName(result);
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                violations.add(Violation.of(parameter, message(error), code(error)));
            }
        }
        return ProblemDefinition.of(ProblemStatus.INVALID, ProblemCodes.VALIDATION)
                .withProperty("violations", violations);
    }

    private String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        // Absent when the class was compiled without -parameters; the index is still better than null.
        return name == null ? "arg" + result.getMethodParameter().getParameterIndex() : name;
    }

    /**
     * The interpolated constraint message. Already localized: the validator resolved it against the
     * application's {@code MessageSource} before wrapping it in a resolvable.
     */
    private String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null || message.isBlank() ? "invalid" : message;
    }

    /** The constraint's own name ({@code Min}, {@code Pattern}), which is its most specific code. */
    private String code(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }

    @Override
    public int getOrder() {
        // Ahead of SpringWebProblemMapper, which would otherwise claim this as a plain ErrorResponse.
        return DEFAULT_MODULE_ORDER - 100;
    }
}
