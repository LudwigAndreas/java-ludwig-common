package ru.ludwigandreas.restclient.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import ru.ludwigandreas.observability.metrics.UriCardinalityLimitingMeterFilter;

/**
 * Adapter onto observability-spring-boot-starter's URI cardinality filter.
 *
 * <p>One method, and it exists purely to keep the optional dependency in one class. Referencing
 * {@code UriCardinalityLimitingMeterFilter} directly from an auto-configuration would load that
 * class while Spring evaluates the configuration's bean methods, which fails when the observability
 * starter is absent - the failure being a {@code NoClassDefFoundError} during context startup rather
 * than the condition simply not matching.
 *
 * <p>The filter itself is not reimplemented. Capping a tag correctly - approximately, without a lock
 * on the meter-registration path, warning once rather than once per request - is already solved
 * there, and solving it a second time would eventually mean two behaviours for the same problem.
 */
public final class UriCardinalityLimit {

    /** The class whose presence this adapter requires, for {@code @ConditionalOnClass}. */
    public static final String MARKER =
            "ru.ludwigandreas.observability.metrics.UriCardinalityLimitingMeterFilter";

    private UriCardinalityLimit() {
    }

    /** A filter capping the {@code uri} tag of meters named {@code meterPrefix}. */
    public static MeterFilter filter(String meterPrefix, int maxTagValues) {
        return new UriCardinalityLimitingMeterFilter(meterPrefix, maxTagValues);
    }
}
