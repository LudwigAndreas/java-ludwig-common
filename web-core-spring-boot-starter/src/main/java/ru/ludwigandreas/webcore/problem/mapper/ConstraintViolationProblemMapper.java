package ru.ludwigandreas.webcore.problem.mapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.webcore.problem.Violation;

/**
 * Renders a {@code ConstraintViolationException} raised outside the MVC argument-resolution path.
 *
 * <p>Spring does not translate this one: a {@code @Validated} service bean, a manual
 * {@code Validator.validate(...)} call, or a JPA pre-persist check throws it straight through, and
 * the default answer is a 500 - a domain rule reported as a server fault. It is a 400 with the same
 * {@code violations} shape as every other validation failure.
 *
 * <p>Only registered when Bean Validation is on the classpath, which is why it lives in its own class
 * rather than as another branch of {@link SpringWebProblemMapper}: a module that uses this starter
 * without a validation provider must not fail to load an advice over a class it never needed.
 */
public class ConstraintViolationProblemMapper implements ExceptionProblemMapper {

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof ConstraintViolationException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        List<Violation> violations = ((ConstraintViolationException) exception).getConstraintViolations()
                .stream()
                .map(this::toViolation)
                .toList();
        return ProblemDefinition.of(ProblemStatus.INVALID, ProblemCodes.VALIDATION)
                .withProperty("violations", violations);
    }

    private Violation toViolation(ConstraintViolation<?> violation) {
        return new Violation(
                field(violation),
                violation.getMessage(),
                constraintName(violation),
                violation.getInvalidValue());
    }

    /**
     * The property path with the invocation's own nodes removed.
     *
     * <p>Bean Validation prefixes a method-level violation with the method it was checked on, so the
     * raw path reads {@code create.sku}. The method name is how the check was invoked, not something
     * the caller sent, so it is dropped and the client sees {@code sku} - the same field name body
     * validation would have reported. The parameter node is kept, because that <em>is</em> the
     * caller's input (and is the only thing identifying it when the class was compiled without
     * {@code -parameters} and it is named {@code arg0}).
     */
    private String field(ConstraintViolation<?> violation) {
        StringBuilder path = new StringBuilder();
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.METHOD
                    || node.getKind() == ElementKind.CONSTRUCTOR
                    || node.getKind() == ElementKind.RETURN_VALUE
                    || node.getKind() == ElementKind.CROSS_PARAMETER) {
                continue;
            }
            if (node.getName() == null) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(node.getName());
        }
        return path.isEmpty() ? violation.getPropertyPath().toString() : path.toString();
    }

    private String constraintName(ConstraintViolation<?> violation) {
        return violation.getConstraintDescriptor() == null
                ? null
                : violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    }

    @Override
    public int getOrder() {
        return DEFAULT_MODULE_ORDER - 100;
    }
}
