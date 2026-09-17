package ru.ludwigandreas.observability.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import ru.ludwigandreas.observability.core.ServiceIdentity;

/**
 * The meter filters that turn Spring Boot's raw {@code http.server.requests} timer into RED metrics
 * an SLO can be built on.
 *
 * <p>Rate, Errors and Duration all come from that one meter - its count is the rate, its
 * {@code outcome}/{@code status} tags give the error ratio, and its distribution gives the latency.
 * Boot publishes it already; what is missing by default is everything that makes it safe and useful
 * at scale, which is what these filters add: bounded tag cardinality, a histogram that can be
 * aggregated across replicas, buckets at the latencies actually promised, and consistent identity
 * tags.
 */
public final class ObservabilityMeterFilters {

    /** Every HTTP server meter Boot and Micrometer publish starts with this. */
    public static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    private ObservabilityMeterFilters() {
    }

    /**
     * Stamps the service identity on every meter.
     *
     * <p>Applied as common tags rather than left to the scraper. Prometheus does add {@code job} and
     * {@code instance} labels at scrape time, but those describe the scrape target, not the process:
     * they are absent from anything pushed rather than scraped, they are lost when series are
     * federated or remote-written into a central store, and they carry no version - so "did the p99
     * move because of the release?" cannot be asked at all.
     */
    public static MeterFilter commonTags(ServiceIdentity identity) {
        Tags tags = Tags.empty();
        for (Map.Entry<String, String> tag : identity.toCommonMetricTags().entrySet()) {
            tags = tags.and(Tag.of(tag.getKey(), tag.getValue()));
        }
        return MeterFilter.commonTags(tags);
    }

    /** See {@link UriCardinalityLimitingMeterFilter} - this is the filter that keeps the registry bounded. */
    public static MeterFilter limitUriCardinality(int maximumTagValues) {
        return new UriCardinalityLimitingMeterFilter(HTTP_SERVER_REQUESTS, maximumTagValues);
    }

    /**
     * Drops HTTP server meters whose URI matches one of {@code ignoredPatterns}.
     *
     * <p>Health probes are the reason. Kubernetes calls the liveness and readiness endpoints every
     * few seconds per replica, so at any realistic traffic level they dominate the request count and
     * pull the latency distribution towards zero - an endpoint's real p99 ends up hidden behind a
     * probe that does nothing but return a constant.
     */
    public static MeterFilter ignorePaths(List<String> ignoredPatterns) {
        PathMatcher pathMatcher = new AntPathMatcher();
        List<String> patterns = List.copyOf(ignoredPatterns);
        return MeterFilter.deny(id -> {
            if (!id.getName().startsWith(HTTP_SERVER_REQUESTS)) {
                return false;
            }
            String uri = id.getTag("uri");
            if (uri == null) {
                return false;
            }
            return patterns.stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
        });
    }

    /**
     * Publishes a latency histogram with explicit SLO buckets for {@code http.server.requests}.
     *
     * <h2>Why a histogram and not client-side percentiles</h2>
     *
     * <p>Micrometer can compute a p99 inside the process and export it as a gauge. That number is
     * correct for one replica and mathematically meaningless once aggregated: the average of four
     * replicas' p99 values is not the fleet's p99, and it is biased low - which means an SLO built on
     * it under-reports exactly during the incidents it exists to catch. A histogram exports the
     * buckets instead, and the percentile is computed at query time across every replica, correctly.
     *
     * <p>{@code merge} rather than a fresh config, so anything the application or another filter has
     * already configured for this meter survives - this adds a histogram, it does not take over the
     * meter's distribution settings.
     */
    public static MeterFilter httpServerHistogram(boolean percentilesHistogram, List<Duration> slo,
            Duration maximumExpectedValue) {
        double[] sloNanos = slo.stream().mapToDouble(Duration::toNanos).toArray();
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                // Exact name, not a prefix. Spring's observation instrumentation also registers a
                // LongTaskTimer called "http.server.requests.active" for in-flight requests, which a
                // prefix match catches - and a LongTaskTimer's default minimum expected value is two
                // minutes, so forcing a thirty-second maximum onto it makes the merged configuration
                // invalid and Micrometer rejects it. The symptom is an exception on the first HTTP
                // request the service ever serves, thrown from inside a metrics filter.
                if (!HTTP_SERVER_REQUESTS.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(percentilesHistogram)
                        .serviceLevelObjectives(sloNanos)
                        .maximumExpectedValue(maximumExpectedValue.toNanos())
                        .build()
                        .merge(config);
            }
        };
    }
}
