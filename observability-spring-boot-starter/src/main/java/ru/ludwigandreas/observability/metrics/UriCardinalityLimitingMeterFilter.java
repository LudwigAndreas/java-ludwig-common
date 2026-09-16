package ru.ludwigandreas.observability.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caps how many distinct {@code uri} tag values one metric may have, folding the rest into
 * {@code OTHER}.
 *
 * <h2>The failure this prevents</h2>
 *
 * <p>{@code http.server.requests} is tagged with the URI <em>template</em>, so in normal operation
 * its cardinality is the number of handlers - a few dozen, permanently. The exception is any request
 * that never reaches a handler: a 404 from a vulnerability scanner, a client with a bad base URL, a
 * path-traversal probe. Those contribute their raw path, and the raw path is chosen by the caller.
 *
 * <p>An in-memory registry holds every series it has ever seen for the process's lifetime, so the
 * result is unbounded heap growth driven entirely by outside input, ending in an
 * {@code OutOfMemoryError} in a service whose actual traffic was fine. It also propagates: the
 * scrape response grows with it, and a Prometheus server ingesting millions of one-off series from
 * one target degrades for everyone else scraped by it.
 *
 * <p>Collapsing to {@code OTHER} keeps the requests counted - the totals and the error rate stay
 * correct - while the tag stops growing. That is a much better trade than dropping the meters
 * outright, which would make a scan look like a drop in traffic.
 *
 * <h2>Boundary: the cap is approximate</h2>
 *
 * <p>The size check and the insert are not one atomic step, so concurrent first-sightings of
 * different URIs can push the set a few entries past the limit. Making it exact would mean
 * serializing every meter registration behind a lock on a path that runs for each new series. The
 * number exists to bound growth, not to be an invariant, and being off by the number of concurrent
 * threads does not affect that.
 */
public class UriCardinalityLimitingMeterFilter implements MeterFilter {

    private static final Logger log = LoggerFactory.getLogger(UriCardinalityLimitingMeterFilter.class);

    private static final String URI_TAG = "uri";
    private static final String OVERFLOW_VALUE = "OTHER";

    private final String meterNamePrefix;
    private final int maximumTagValues;
    private final Set<String> observedUris = ConcurrentHashMap.newKeySet();

    /** Ensures the "cap reached" warning is emitted once, not once per excess request. */
    private final AtomicBoolean capReported = new AtomicBoolean();

    /**
     * @throws IllegalArgumentException if {@code maximumTagValues} is negative
     */
    public UriCardinalityLimitingMeterFilter(String meterNamePrefix, int maximumTagValues) {
        // Zero is meaningful - it collapses every URI, which is a legitimate way to switch the tag off
        // entirely - but a negative cap is always a configuration mistake, and its effect is identical
        // to zero. Accepting it would silently erase every per-endpoint metric the service publishes,
        // with dashboards that keep rendering because the meter still exists.
        if (maximumTagValues < 0) {
            throw new IllegalArgumentException(
                    "ludwig.observability.metrics.http.max-uri-tags must not be negative but was "
                            + maximumTagValues);
        }
        this.meterNamePrefix = meterNamePrefix;
        this.maximumTagValues = maximumTagValues;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        if (!id.getName().startsWith(meterNamePrefix)) {
            return id;
        }
        String uri = id.getTag(URI_TAG);
        if (uri == null || observedUris.contains(uri)) {
            return id;
        }
        if (observedUris.size() < maximumTagValues) {
            observedUris.add(uri);
            return id;
        }
        if (capReported.compareAndSet(false, true)) {
            log.warn("Reached the limit of {} distinct '{}' tag values on '{}'; further URIs are recorded as '{}'. "
                            + "This usually means unmatched requests are reaching the metric - check for 404 traffic "
                            + "before raising ludwig.observability.metrics.http.max-uri-tags",
                    maximumTagValues, URI_TAG, meterNamePrefix, OVERFLOW_VALUE);
        }
        // withTag, never replaceTags: replaceTags substitutes the meter's ENTIRE tag set, which would
        // discard method, status and outcome - the three tags the R and E of RED are computed from -
        // and leave a metric that counts requests but can no longer distinguish an error from a
        // success. withTag rewrites only the uri key.
        return id.withTag(Tag.of(URI_TAG, OVERFLOW_VALUE));
    }
}
