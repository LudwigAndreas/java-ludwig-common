package ru.ludwigandreas.export.engine;

import java.util.Map;
import ru.ludwigandreas.export.api.ReportParameters;

/**
 * Turns the strings a request carries into the definition's typed parameter object.
 *
 * <p>A seam because the binding and the validation are the application's conventions, not this
 * module's: a service already has a {@code Validator} wired to its own message source and its own
 * locale resolution, and a binder here that resolved constraint messages differently would produce
 * violations in a different language from every other 400 the service returns.
 *
 * <p>The contract is that a failure is a localized 400 with per-field violations, which is what the
 * shipped implementation produces by going through Spring's binder and Bean Validation.
 */
@FunctionalInterface
public interface ReportParameterBinder {

    /**
     * Binds and validates.
     *
     * @param type   the definition's declared parameter type
     * @param values the raw values from the request
     * @param <P>    the parameter type
     * @return the bound, validated object
     */
    <P extends ReportParameters> P bind(Class<P> type, Map<String, String> values);
}
