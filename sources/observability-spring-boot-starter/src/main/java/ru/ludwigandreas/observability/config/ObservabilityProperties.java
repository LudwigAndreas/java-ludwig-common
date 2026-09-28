package ru.ludwigandreas.observability.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Everything the starter can be configured with, under {@code ludwig.observability}.
 *
 * <p>The defaults are the shape a service should ship with, not a minimal starting point: traces
 * exported over OTLP, one correlation id crossing every hop, JSON logs carrying the trace and that
 * correlation id, RED metrics with bounded tag cardinality, and liveness/readiness split apart. A
 * service that configures nothing gets all of it.
 *
 * <p>Where a knob exists, it exists because the right value differs per deployment - the sampling
 * rate, the OTLP endpoint, whether the console is a human's terminal - and not because the default
 * was unclear.
 */
@ConfigurationProperties(prefix = "ludwig.observability")
public class ObservabilityProperties {

    /** Master switch for the whole starter's autoconfiguration. */
    private boolean enabled = true;

    @NestedConfigurationProperty
    private final Service service = new Service();

    @NestedConfigurationProperty
    private final Tracing tracing = new Tracing();

    @NestedConfigurationProperty
    private final Correlation correlation = new Correlation();

    @NestedConfigurationProperty
    private final Logging logging = new Logging();

    @NestedConfigurationProperty
    private final Metrics metrics = new Metrics();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Service getService() {
        return service;
    }

    public Tracing getTracing() {
        return tracing;
    }

    public Correlation getCorrelation() {
        return correlation;
    }

    public Logging getLogging() {
        return logging;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * Who this process is: {@code ludwig.observability.service.*}.
     *
     * <p>These five values are stamped identically onto metrics, spans and logs - see
     * {@link ru.ludwigandreas.observability.core.ServiceIdentity} for why that matters.
     */
    public static class Service {

        /** Defaults to {@code spring.application.name}; see {@code ObservabilityCoreAutoConfiguration}. */
        private String name;

        /** Bounded context or owning team. Disambiguates two services that share a name. */
        private String namespace;

        /**
         * The deployed build. Defaults to the {@code Implementation-Version} in the jar manifest, so
         * a normally-built Spring Boot application gets this for free.
         */
        private String version;

        /**
         * Deployment tier: {@code prod}, {@code staging}, {@code dev}. Defaults to the first active
         * Spring profile, which is right often enough to be a useful default and wrong often enough
         * that a deployment should set it explicitly.
         */
        private String environment;

        /** This replica. Defaults to {@code HOSTNAME} - the pod name, under Kubernetes. */
        private String instance;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }

        public String getInstance() {
            return instance;
        }

        public void setInstance(String instance) {
            this.instance = instance;
        }
    }

    /** Distributed tracing: {@code ludwig.observability.tracing.*}. */
    public static class Tracing {

        /**
         * Whether this module's tracing additions are registered. Switching it off leaves Spring
         * Boot's own tracing autoconfiguration alone - this controls the sampler override, the span
         * filters and the identity attributes, not tracing itself. To disable tracing outright, use
         * Boot's {@code management.tracing.enabled=false}.
         */
        private boolean enabled = true;

        /**
         * Request paths whose server spans are never exported, as Ant patterns.
         *
         * <p>Health probes are the reason this defaults to something rather than nothing: Kubernetes
         * calls {@code /actuator/health/liveness} every few seconds forever, and at a realistic
         * probe interval those spans outnumber real traffic in the tracing backend while carrying no
         * information at all. They are dropped at export, not at sampling, so a probe that is part
         * of a larger incoming trace still keeps its parent intact.
         */
        private List<String> excludedPaths = new ArrayList<>(List.of("/actuator/**"));

        @NestedConfigurationProperty
        private final Sampling sampling = new Sampling();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getExcludedPaths() {
            return excludedPaths;
        }

        public void setExcludedPaths(List<String> excludedPaths) {
            this.excludedPaths = excludedPaths;
        }

        public Sampling getSampling() {
            return sampling;
        }

        /** Head-based sampling and the escape hatch from it: {@code ...tracing.sampling.*}. */
        public static class Sampling {

            /**
             * Whether this module installs its own {@code Sampler}. Off means Spring Boot's plain
             * probability sampler stays in place and {@link #forceHeader} does nothing.
             */
            private boolean enabled = true;

            /**
             * Header that forces the current request to be sampled regardless of the configured
             * probability, for reproducing a reported fault on demand.
             *
             * <p>This is genuinely useful and genuinely dangerous, which is why {@link #trustedForceHeader}
             * exists: anything that can set this header can make a service trace 100% of its traffic.
             * Set it to empty to remove the capability entirely.
             */
            private String forceHeader = "X-Ludwig-Force-Trace";

            /**
             * Whether {@link #forceHeader} is honoured when it arrives from outside.
             *
             * <p>Off by default, and that default is the safe one: a public edge that forwards
             * unknown headers would otherwise let any caller turn on full sampling for a service,
             * which is a cheap denial-of-wallet attack against the tracing backend. Turn it on only
             * where the edge strips or authorizes the header - which is exactly why it is a separate
             * switch from {@link #forceHeader} rather than implied by it.
             */
            private boolean trustedForceHeader = false;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getForceHeader() {
                return forceHeader;
            }

            public void setForceHeader(String forceHeader) {
                this.forceHeader = forceHeader;
            }

            public boolean isTrustedForceHeader() {
                return trustedForceHeader;
            }

            public void setTrustedForceHeader(boolean trustedForceHeader) {
                this.trustedForceHeader = trustedForceHeader;
            }
        }
    }

    /** The correlation id and how it travels: {@code ludwig.observability.correlation.*}. */
    public static class Correlation {

        /**
         * Whether the correlation id is resolved, propagated and logged.
         *
         * <p>A correlation id is not a trace id and does not replace one. The trace id identifies
         * one distributed execution and only exists while tracing is on and the request was sampled;
         * the correlation id identifies a piece of <em>business</em> work - an order, a batch run, a
         * retried webhook - survives across retries that each get their own trace, and is present on
         * 100% of requests because it is never sampled away. Support quotes the correlation id;
         * engineers open the trace id.
         */
        private boolean enabled = true;

        /** Header the id is read from and written back on. */
        private String headerName = "X-Correlation-Id";

        /**
         * Additional inbound headers accepted as the correlation id, in order, when
         * {@link #headerName} is absent. Lets a service sit behind an edge that already stamps its
         * own request id without every caller having to learn a new header name.
         */
        private List<String> additionalInboundHeaders = new ArrayList<>(List.of("X-Request-Id"));

        /** MDC key the id is published under, and therefore the JSON log field it appears in. */
        private String mdcKey = "correlationId";

        /** Whether the resolved id is echoed on the response, so a caller can log what it was given. */
        private boolean includeResponseHeader = true;

        /**
         * Response header carrying the trace id, or empty to omit it. Turns "it was slow at about
         * two" into a single trace lookup, because the caller recorded the id at the time.
         */
        private String traceIdResponseHeader = "X-Trace-Id";

        /** Whether an absent inbound id is generated rather than left empty. */
        private boolean generateIfAbsent = true;

        /**
         * Longest accepted inbound id. Anything longer is replaced by a generated one rather than
         * truncated.
         *
         * <p>This bound is load-bearing, not tidiness. The id is echoed into a response header, into
         * every log line and onto a Kafka record header, all from an unauthenticated inbound string:
         * without a cap, one caller sending a megabyte header writes that megabyte to the log
         * aggregator on every line it touches.
         */
        private int maxLength = 128;

        /**
         * Characters permitted in an inbound id, as a regular expression matched against the whole
         * value. Anything not matching is replaced by a generated id.
         *
         * <p>Restrictive on purpose: the value reaches an HTTP response header, where a newline
         * would be a response-splitting bug, and a log aggregator's JSON, where control characters
         * corrupt the record. Accepting only unreserved URL characters makes both impossible at the
         * point of entry instead of at each point of use.
         */
        private String allowedPattern = "[A-Za-z0-9_.:-]+";

        /** Whether the id is carried on Kafka records: {@code ludwig.observability.correlation.kafka.*}. */
        @NestedConfigurationProperty
        private final Kafka kafka = new Kafka();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public List<String> getAdditionalInboundHeaders() {
            return additionalInboundHeaders;
        }

        public void setAdditionalInboundHeaders(List<String> additionalInboundHeaders) {
            this.additionalInboundHeaders = additionalInboundHeaders;
        }

        public String getMdcKey() {
            return mdcKey;
        }

        public void setMdcKey(String mdcKey) {
            this.mdcKey = mdcKey;
        }

        public boolean isIncludeResponseHeader() {
            return includeResponseHeader;
        }

        public void setIncludeResponseHeader(boolean includeResponseHeader) {
            this.includeResponseHeader = includeResponseHeader;
        }

        public String getTraceIdResponseHeader() {
            return traceIdResponseHeader;
        }

        public void setTraceIdResponseHeader(String traceIdResponseHeader) {
            this.traceIdResponseHeader = traceIdResponseHeader;
        }

        public boolean isGenerateIfAbsent() {
            return generateIfAbsent;
        }

        public void setGenerateIfAbsent(boolean generateIfAbsent) {
            this.generateIfAbsent = generateIfAbsent;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public String getAllowedPattern() {
            return allowedPattern;
        }

        public void setAllowedPattern(String allowedPattern) {
            this.allowedPattern = allowedPattern;
        }

        public Kafka getKafka() {
            return kafka;
        }

        /** Kafka-side propagation: {@code ludwig.observability.correlation.kafka.*}. */
        public static class Kafka {

            /**
             * Whether produced records carry the correlation id and consumed records restore it.
             *
             * <p>Without this, a trace ends at the broker: the W3C {@code traceparent} header that
             * Spring Kafka's own observation support propagates only links spans while the consumer
             * is sampled, and it carries no business identity. The correlation id is what lets
             * "order 4711 never arrived" be one log query spanning producer, broker and every
             * consumer that touched it.
             */
            private boolean enabled = true;

            /** Record header the id is stored in. */
            private String headerName = "X-Correlation-Id";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getHeaderName() {
                return headerName;
            }

            public void setHeaderName(String headerName) {
                this.headerName = headerName;
            }
        }
    }

    /** Log output and log levels: {@code ludwig.observability.logging.*}. */
    public static class Logging {

        @NestedConfigurationProperty
        private final Json json = new Json();

        @NestedConfigurationProperty
        private final Levels levels = new Levels();

        public Json getJson() {
            return json;
        }

        public Levels getLevels() {
            return levels;
        }

        /** Structured JSON output: {@code ludwig.observability.logging.json.*}. */
        public static class Json {

            /**
             * Whether the console appender writes one JSON object per line instead of Logback's
             * human-readable pattern.
             *
             * <p><strong>On by default</strong>, which is a deliberate and slightly opinionated
             * choice. A log line only becomes queryable - "every ERROR for this correlation id
             * across all four services" - once the aggregator gets fields rather than a sentence,
             * and a starter whose structured logging has to be switched on is one every service
             * forgets to switch on. The cost lands on developers reading a local console, which is
             * why {@code ludwig.observability.logging.json.enabled: false} belongs in the
             * {@code local} profile of the service template.
             */
            private boolean enabled = true;

            /**
             * Which field names the JSON uses: {@code ecs}, {@code otel} or {@code flat}.
             *
             * <p>Pick the one the aggregator already understands and no ingest-time mapping is
             * needed: {@code ecs} for Elastic/OpenSearch, {@code otel} for an OpenTelemetry
             * collector or Loki, {@code flat} for a plain shallow shape.
             */
            private FieldSet fieldSet = FieldSet.ECS;

            /** Whether the MDC is written out at all. */
            private boolean includeMdc = true;

            /**
             * MDC keys to write, or empty for all of them.
             *
             * <p>Worth setting in a service that puts request-scoped data in the MDC: the MDC is an
             * open map, so anything anyone ever puts there is logged on every subsequent line of
             * that thread, and an allow-list is the only thing that keeps a customer identifier from
             * silently becoming part of the log stream.
             */
            private List<String> mdcIncludeKeys = new ArrayList<>();

            /** MDC keys never written. Applied after {@link #mdcIncludeKeys}. */
            private List<String> mdcExcludeKeys = new ArrayList<>();

            /**
             * Whether the MDC is nested under one object ({@code "labels": {...}}) or flattened to
             * top-level fields.
             *
             * <p>Nested by default: flattening lets an MDC key collide with - and overwrite - a
             * structural field like {@code message} or {@code trace.id}, which breaks the aggregator's
             * mapping for the whole index rather than for one line.
             */
            private boolean nestMdc = true;

            /** Object name the MDC is nested under when {@link #nestMdc} is set. */
            private String mdcFieldName = "labels";

            /**
             * MDC keys and structured-argument keys whose values are replaced with
             * {@code "***"}, matched case-insensitively as substrings.
             *
             * <p>Not a security control - it is a backstop. Secrets should not reach the MDC at all;
             * this exists because they occasionally do, and a log aggregator is the worst place to
             * discover it, being the one system the whole company can search.
             */
            private List<String> maskedKeys = new ArrayList<>(
                    List.of("password", "secret", "token", "credential", "authorization", "apikey", "api-key"));

            /** Constant fields added to every line: build id, region, cluster, whatever the estate sorts by. */
            private Map<String, String> staticFields = new LinkedHashMap<>();

            /**
             * Longest logged message before truncation, or {@code 0} for no limit.
             *
             * <p>Default caps a single line at 16 KiB. One service logging a whole response body in a
             * loop is the normal way a log pipeline falls over, and dropping the line at the source
             * is cheaper than the aggregator rejecting it after transferring it.
             */
            private int maxMessageLength = 16384;

            /**
             * Longest rendered stack trace before truncation, or {@code 0} for no limit.
             *
             * <p>Deep frameworks produce stack traces that dwarf every other field; the first
             * frames are the ones anybody reads, and truncation is marked in the output so nobody
             * mistakes a cut trace for a complete one.
             */
            private int maxStackTraceLength = 12288;

            /** Whether the logging thread's name is included. */
            private boolean includeThreadName = true;

            /** Whether a {@code MarkerFactory} marker, when present, is included. */
            private boolean includeMarkers = true;

            /**
             * Whether the raw pre-interpolation message is kept alongside the formatted one.
             *
             * <p>Off by default because it roughly doubles the message bytes. On, it becomes possible
             * to group "the same log statement" across lines whose interpolated values differ, which
             * is how you count occurrences of one problem rather than of one phrasing.
             */
            private boolean includeMessageTemplate = false;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public FieldSet getFieldSet() {
                return fieldSet;
            }

            public void setFieldSet(FieldSet fieldSet) {
                this.fieldSet = fieldSet;
            }

            public boolean isIncludeMdc() {
                return includeMdc;
            }

            public void setIncludeMdc(boolean includeMdc) {
                this.includeMdc = includeMdc;
            }

            public List<String> getMdcIncludeKeys() {
                return mdcIncludeKeys;
            }

            public void setMdcIncludeKeys(List<String> mdcIncludeKeys) {
                this.mdcIncludeKeys = mdcIncludeKeys;
            }

            public List<String> getMdcExcludeKeys() {
                return mdcExcludeKeys;
            }

            public void setMdcExcludeKeys(List<String> mdcExcludeKeys) {
                this.mdcExcludeKeys = mdcExcludeKeys;
            }

            public boolean isNestMdc() {
                return nestMdc;
            }

            public void setNestMdc(boolean nestMdc) {
                this.nestMdc = nestMdc;
            }

            public String getMdcFieldName() {
                return mdcFieldName;
            }

            public void setMdcFieldName(String mdcFieldName) {
                this.mdcFieldName = mdcFieldName;
            }

            public List<String> getMaskedKeys() {
                return maskedKeys;
            }

            public void setMaskedKeys(List<String> maskedKeys) {
                this.maskedKeys = maskedKeys;
            }

            public Map<String, String> getStaticFields() {
                return staticFields;
            }

            public void setStaticFields(Map<String, String> staticFields) {
                this.staticFields = staticFields;
            }

            public int getMaxMessageLength() {
                return maxMessageLength;
            }

            public void setMaxMessageLength(int maxMessageLength) {
                this.maxMessageLength = maxMessageLength;
            }

            public int getMaxStackTraceLength() {
                return maxStackTraceLength;
            }

            public void setMaxStackTraceLength(int maxStackTraceLength) {
                this.maxStackTraceLength = maxStackTraceLength;
            }

            public boolean isIncludeThreadName() {
                return includeThreadName;
            }

            public void setIncludeThreadName(boolean includeThreadName) {
                this.includeThreadName = includeThreadName;
            }

            public boolean isIncludeMarkers() {
                return includeMarkers;
            }

            public void setIncludeMarkers(boolean includeMarkers) {
                this.includeMarkers = includeMarkers;
            }

            public boolean isIncludeMessageTemplate() {
                return includeMessageTemplate;
            }

            public void setIncludeMessageTemplate(boolean includeMessageTemplate) {
                this.includeMessageTemplate = includeMessageTemplate;
            }
        }

        /** Runtime log levels: {@code ludwig.observability.logging.levels.*}. */
        public static class Levels {

            /**
             * Whether {@code logging.level.*} keys arriving from a hot-reloaded source are applied to
             * the running logging system.
             *
             * <p>Requires the hot-reload starter on the classpath; without it this does nothing. The
             * point is that raising one package to DEBUG during an incident becomes a config change
             * a watched file or Vault secret delivers - no restart, which would destroy the state
             * being investigated, and no authenticated actuator call to every replica individually.
             */
            private boolean hotReloadEnabled = true;

            /**
             * Whether a level reverts to its startup value when its key disappears from the reloaded
             * source.
             *
             * <p>On, because the failure mode otherwise is silent and expensive: someone raises a
             * chatty package to DEBUG at 3am, deletes the line the next morning, and the service
             * keeps logging at DEBUG until its next restart - possibly weeks, at full volume.
             */
            private boolean revertOnRemoval = true;

            public boolean isHotReloadEnabled() {
                return hotReloadEnabled;
            }

            public void setHotReloadEnabled(boolean hotReloadEnabled) {
                this.hotReloadEnabled = hotReloadEnabled;
            }

            public boolean isRevertOnRemoval() {
                return revertOnRemoval;
            }

            public void setRevertOnRemoval(boolean revertOnRemoval) {
                this.revertOnRemoval = revertOnRemoval;
            }
        }

        /** Field-naming schemes the JSON encoder can emit. */
        public enum FieldSet {

            /** Elastic Common Schema: {@code @timestamp}, {@code log.level}, {@code trace.id}. */
            ECS,

            /** OpenTelemetry log-data-model names: {@code Timestamp}, {@code SeverityText}, {@code TraceId}. */
            OTEL,

            /** Shallow snake_case: {@code timestamp}, {@code level}, {@code trace_id}. */
            FLAT
        }
    }

    /** Metrics: {@code ludwig.observability.metrics.*}. */
    public static class Metrics {

        /** Whether this module's meter filters and aspects are registered. */
        private boolean enabled = true;

        /**
         * Whether the service identity is applied as common tags to every meter.
         *
         * <p>Worth leaving on even when the scrape target already adds {@code job} and
         * {@code instance} labels: those are the scraper's view, and they are absent from anything
         * pushed rather than scraped, and from anything federated into a central store.
         */
        private boolean commonTags = true;

        @NestedConfigurationProperty
        private final Http http = new Http();

        @NestedConfigurationProperty
        private final Aspects aspects = new Aspects();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isCommonTags() {
            return commonTags;
        }

        public void setCommonTags(boolean commonTags) {
            this.commonTags = commonTags;
        }

        public Http getHttp() {
            return http;
        }

        public Aspects getAspects() {
            return aspects;
        }

        /** RED metrics on HTTP endpoints: {@code ludwig.observability.metrics.http.*}. */
        public static class Http {

            /** Whether {@code http.server.requests} is shaped by this module at all. */
            private boolean enabled = true;

            /**
             * Maximum number of distinct {@code uri} tag values before further ones collapse to
             * {@code OTHER}.
             *
             * <p>This is the single most important setting in the module. {@code http.server.requests}
             * is tagged by URI <em>template</em>, so it is normally bounded by the number of
             * handlers - but any request that never reaches a handler (404 scans, a misconfigured
             * client, a path-traversal probe) can contribute its raw path, and the series count then
             * grows with attacker input until the registry exhausts heap and takes the service down
             * with it. The cap turns that outage into a degraded tag.
             */
            private int maxUriTags = 100;

            /**
             * Whether a latency histogram is published, enabling percentiles computed correctly
             * across instances.
             *
             * <p>On: the alternative is client-side percentiles, which cannot be aggregated - the
             * average of four instances' p99 is not the fleet p99, and an SLO built on it is wrong in
             * the direction that hides incidents.
             */
            private boolean percentilesHistogram = true;

            /**
             * Latency buckets published as explicit service-level objectives.
             *
             * <p>These are the boundaries an SLO burn-rate alert is computed from, so they should be
             * the latencies actually promised. The defaults span a typical web API's range.
             */
            private List<Duration> slo = new ArrayList<>(List.of(
                    Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200),
                    Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(5), Duration.ofSeconds(10)));

            /** Longest latency the histogram tracks; anything slower lands in the overflow bucket. */
            private Duration maximumExpectedValue = Duration.ofSeconds(30);

            /**
             * Request paths excluded from {@code http.server.requests}, as Ant patterns.
             *
             * <p>Probes again: at a few seconds' interval they dominate the request rate and drag the
             * latency distribution towards zero, so an endpoint's real p99 disappears behind the
             * health check's.
             */
            private List<String> ignoredPaths = new ArrayList<>(List.of("/actuator/**"));

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getMaxUriTags() {
                return maxUriTags;
            }

            public void setMaxUriTags(int maxUriTags) {
                this.maxUriTags = maxUriTags;
            }

            public boolean isPercentilesHistogram() {
                return percentilesHistogram;
            }

            public void setPercentilesHistogram(boolean percentilesHistogram) {
                this.percentilesHistogram = percentilesHistogram;
            }

            public List<Duration> getSlo() {
                return slo;
            }

            public void setSlo(List<Duration> slo) {
                this.slo = slo;
            }

            public Duration getMaximumExpectedValue() {
                return maximumExpectedValue;
            }

            public void setMaximumExpectedValue(Duration maximumExpectedValue) {
                this.maximumExpectedValue = maximumExpectedValue;
            }

            public List<String> getIgnoredPaths() {
                return ignoredPaths;
            }

            public void setIgnoredPaths(List<String> ignoredPaths) {
                this.ignoredPaths = ignoredPaths;
            }
        }

        /** Annotation-driven instrumentation: {@code ludwig.observability.metrics.aspects.*}. */
        public static class Aspects {

            /**
             * Whether {@code @Timed}, {@code @Counted} and {@code @Observed} are honoured on the
             * application's own beans. Requires Spring AOP; silently inert without it.
             */
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
