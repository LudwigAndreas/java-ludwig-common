package ru.ludwigandreas.odatafilter.metrics;

import java.time.Duration;

/**
 * Instrumentation hook for {@code ODataFilterService.parse}. {@link NoopODataFilterMetrics} is always
 * registered as a fallback; {@link MicrometerODataFilterMetrics} replaces it when both Micrometer is on
 * the classpath and {@code odata.filter.metrics.enabled=true} (the default).
 */
public interface ODataFilterMetrics {

    /** A filter was parsed, validated and translated to a predicate successfully. */
    void recordFilterApplied(String entityType);

    /**
     * A filter was rejected - syntax error, depth/page-size limit, field access denied, or a custom
     * {@code FilterValidator} veto. Worth alerting on a sustained spike: it's either a client bug or
     * someone probing for what's filterable.
     */
    void recordFilterRejected(String entityType, String reason);

    /** Time spent in {@code parse} regardless of outcome. */
    void recordParseDuration(String entityType, Duration duration);

    /**
     * A caller asked what it may filter on, and was told.
     *
     * <p>Counted separately from an applied filter and from a rejection so that the two signals can be told
     * apart. A rise in rejections is documented as "either a client bug or someone probing for what's
     * filterable"; this is what distinguishes the clients that asked properly from the ones probing, which
     * the rejection counter alone cannot do.
     *
     * @param entityType the published name the document was served for
     */
    void recordMetadataServed(String entityType);
}
