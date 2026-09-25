package ru.ludwigandreas.export.engine;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.ReportParameters;
import ru.ludwigandreas.export.exception.ExportProblemCodes;
import ru.ludwigandreas.export.exception.ReportParametersInvalidException;
import ru.ludwigandreas.webcore.problem.Violation;

/**
 * Binds report parameters with Spring Boot's own binder, and validates with Bean Validation.
 *
 * <h2>Why Spring's binder rather than Jackson</h2>
 *
 * <p>Because the input is a flat map of strings - that is what arrives from a query string and what a
 * saved configuration stores - and Spring's binder is the part of the platform that already knows how
 * to turn {@code "2026-03-01"} into a {@code LocalDate} using the same converters the rest of the
 * application uses. Deserializing through Jackson would introduce a second set of conversion rules
 * that differ from the ones every controller in the service already applies.
 *
 * <p>It also handles records, which is what a {@code ReportParameters} type almost always is.
 *
 * <h2>Violations are per field</h2>
 *
 * <p>A parameter object that fails validation produces one problem carrying every violation, not one
 * problem per attempt. Somebody correcting a report request at a form should see all of the mistakes
 * at once, for the same reason the startup validators report every problem together.
 */
public class SpringReportParameterBinder implements ReportParameterBinder {

    private final Validator validator;

    /**
     * Creates the binder.
     *
     * @param validator the application's own validator, or null when it has none - in which case
     *                  constraints on a parameter record are not enforced, and the binder says so in
     *                  the only way it can, by binding without checking rather than by pretending
     */
    public SpringReportParameterBinder(Validator validator) {
        this.validator = validator;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <P extends ReportParameters> P bind(Class<P> type, Map<String, String> values) {
        if (type.equals(NoParameters.class)) {
            return (P) NoParameters.INSTANCE;
        }
        P bound = bindOrFail(type, values);
        validate(bound);
        return bound;
    }

    private <P> P bindOrFail(Class<P> type, Map<String, String> values) {
        Map<String, Object> source = new LinkedHashMap<>(values);
        try {
            return new Binder(new MapConfigurationPropertySource(source))
                    .bindOrCreate("", Bindable.of(type));
        } catch (BindException e) {
            // The binder's own message names the property and the type it could not produce, which is
            // exactly the information a requester needs - but it is a developer-facing English
            // sentence, so it goes to the log as the cause and the caller gets the localized code.
            throw new ReportParametersInvalidException(
                    Violation.of(propertyOf(e), "could not be converted to the declared type",
                            ExportProblemCodes.PARAMETER_INVALID), e);
        }
    }

    private <P> void validate(P bound) {
        if (validator == null) {
            return;
        }
        Set<ConstraintViolation<P>> violations = validator.validate(bound);
        if (violations.isEmpty()) {
            return;
        }
        throw new ReportParametersInvalidException(violations.stream()
                .map(violation -> Violation.of(violation.getPropertyPath().toString(),
                        violation.getMessage(),
                        violation.getConstraintDescriptor().getAnnotation().annotationType()
                                .getSimpleName()))
                .toList(), null);
    }

    private String propertyOf(BindException e) {
        return e.getProperty() == null ? "" : e.getProperty().toString();
    }
}
