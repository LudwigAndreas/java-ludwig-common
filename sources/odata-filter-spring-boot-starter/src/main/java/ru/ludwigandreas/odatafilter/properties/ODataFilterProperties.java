package ru.ludwigandreas.odatafilter.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Global defaults for every entity filtered through this library. Any of the numeric limits can
 * be overridden per entity with {@code @FilterPolicy} on the entity class.
 *
 * <h2>Why the bounds are checked at startup</h2>
 *
 * <p>Every one of these is at least 1, and none of them was checked until
 * {@code configuration-properties.configuration-properties-are-validated} was switched on. A
 * {@code max-page-size} of 0 was accepted and then failed <em>every</em> query at the first request, with a
 * message about {@code $top} that pointed at the caller rather than at the configuration. A bound that can
 * only be wrong is better refused while the context is starting, which is the whole argument for that rule.
 *
 * <p>One thing is deliberately <strong>not</strong> checked here: that {@code defaultPageSize} is no larger
 * than {@code maxPageSize}. It is a cross-field invariant, so it needs a class-level constraint or a
 * {@code Validator} bean rather than an annotation on a member, and the resolved per-entity policy - where
 * a {@code @FilterPolicy} override can change either - is the only place the comparison is actually
 * meaningful. Stated rather than silently omitted.
 *
 * <h2>Why this is not in {@code config}</h2>
 *
 * <p>It used to be, beside the auto-configuration classes, and that single package playing two roles was the
 * whole of this module's package-cycle problem: {@code core}, {@code web} and {@code policy} each read these
 * properties, while the wiring in {@code config} references every package in the module to build its beans.
 * Those three inbound edges closed <strong>twenty</strong> distinct cycles through {@code config}, which is
 * every cycle the module had.
 *
 * <p>Bound properties are a value that any layer may read; wiring is the thing that assembles the layers and
 * must therefore be a leaf. Keeping them apart is what makes {@code config} depended upon by nothing, and it
 * is the same split {@code file-ingest-spring-boot-starter} made for the same reason - that module calls its
 * wiring package {@code autoconfigure} and keeps properties in {@code config}, the opposite assignment, and
 * the inconsistency is deliberate: an auto-configuration class's fully-qualified name is configuration API
 * (a deployment excludes one by name in YAML, which no compiler checks), so renaming the wiring package would
 * break an exclusion silently at runtime, while moving this class breaks a compile.
 */
@ConfigurationProperties(prefix = "odata.filter")
@Validated
public class ODataFilterProperties {

    /** Default {@code odata.filter.max-depth}: max boolean nesting depth of a parsed $filter. */
    @Min(1)
    private int maxDepth = 4;

    /** Default {@code odata.filter.max-page-size}: max value callers may pass as $top. */
    @Min(1)
    private int maxPageSize = 200;

    /** {@code odata.filter.default-page-size}: page size used when $top is omitted. */
    @Min(1)
    private int defaultPageSize = 20;

    /** {@code odata.filter.max-nested-property-depth}: max '/'-separated segments in a property path. */
    @Min(1)
    private int maxNestedPropertyDepth = 2;

    /** {@code odata.filter.max-expression-length}: max raw length of $filter, rejected before parsing. */
    @Min(1)
    private int maxExpressionLength = 2048;

    /** What to do when a caller's $top exceeds {@link #maxPageSize}. */
    private PageSizeExceededStrategy pageSizeExceededStrategy = PageSizeExceededStrategy.REJECT;

    @NestedConfigurationProperty
    @Valid
    private final Web web = new Web();

    @NestedConfigurationProperty
    @Valid
    private final Metrics metrics = new Metrics();

    /** Filter-policy discovery. Off until a base path is set - see {@link Metadata}. */
    @Valid
    private final Metadata metadata = new Metadata();

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxNestedPropertyDepth() {
        return maxNestedPropertyDepth;
    }

    public void setMaxNestedPropertyDepth(int maxNestedPropertyDepth) {
        this.maxNestedPropertyDepth = maxNestedPropertyDepth;
    }

    public int getMaxExpressionLength() {
        return maxExpressionLength;
    }

    public void setMaxExpressionLength(int maxExpressionLength) {
        this.maxExpressionLength = maxExpressionLength;
    }

    public PageSizeExceededStrategy getPageSizeExceededStrategy() {
        return pageSizeExceededStrategy;
    }

    public void setPageSizeExceededStrategy(PageSizeExceededStrategy pageSizeExceededStrategy) {
        this.pageSizeExceededStrategy = pageSizeExceededStrategy;
    }

    public Web getWeb() {
        return web;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public enum PageSizeExceededStrategy {
        /** Reject the request with {@link ru.ludwigandreas.odatafilter.exception.PageSizeExceededException}. */
        REJECT,
        /** Silently cap $top at the configured maximum. */
        CLAMP
    }

    public static class Web {

        /** Whether to register the {@code @RestControllerAdvice} that maps our exceptions to {@code ProblemDetail}. */
        private boolean problemDetailAdviceEnabled = true;

        /**
         * If true, only {@code $filter}/{@code $top}/... are read; the non-prefixed aliases are
         * ignored. The aliases exist because many HTTP clients and API gateways mangle or reject a
         * leading {@code $} in a query parameter name.
         */
        private boolean dollarPrefixedParametersOnly = false;

        public boolean isProblemDetailAdviceEnabled() {
            return problemDetailAdviceEnabled;
        }

        public void setProblemDetailAdviceEnabled(boolean problemDetailAdviceEnabled) {
            this.problemDetailAdviceEnabled = problemDetailAdviceEnabled;
        }

        public boolean isDollarPrefixedParametersOnly() {
            return dollarPrefixedParametersOnly;
        }

        public void setDollarPrefixedParametersOnly(boolean dollarPrefixedParametersOnly) {
            this.dollarPrefixedParametersOnly = dollarPrefixedParametersOnly;
        }
    }

    public static class Metrics {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * The filter-policy discovery endpoint, which lets a client learn what it may filter on instead of
     * sending filters and reading the rejections.
     */
    public static class Metadata {

        /**
         * Where to mount the endpoint, e.g. {@code /api/v1/filter-metadata}. <strong>Unset by default, and
         * unset means there is no endpoint at all</strong> - not an endpoint that refuses.
         *
         * <p>That distinction is the point. A metadata document is a map of the queryable surface, useful
         * to a client and equally useful to someone enumerating it, so the surface should not exist until a
         * deployment has decided it should. An endpoint that existed and answered 403 would still confirm
         * the feature is there and still be one misconfiguration away from answering.
         *
         * <p>Setting it is necessary and not sufficient: an entity also has to declare
         * {@code @FilterPolicy(metadataName = ...)}, and the document is filtered per caller on top of
         * that.
         */
        private String basePath;

        /**
         * Switches discovery off while leaving {@link #basePath} configured.
         *
         * <p>For turning the feature off in one environment without editing the path out of a shared
         * configuration file - which is how a path gets lost and silently not restored.
         */
        private boolean enabled = true;

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** Whether a deployment has actually asked for the endpoint. */
        public boolean isMounted() {
            return enabled && basePath != null && !basePath.isBlank();
        }
    }
}
