package ru.ludwigandreas.odatafilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Global defaults for every entity filtered through this library. Any of the numeric limits can
 * be overridden per entity with {@code @FilterPolicy} on the entity class.
 */
@ConfigurationProperties(prefix = "odata.filter")
public class ODataFilterProperties {

    /** Default {@code odata.filter.max-depth}: max boolean nesting depth of a parsed $filter. */
    private int maxDepth = 4;

    /** Default {@code odata.filter.max-page-size}: max value callers may pass as $top. */
    private int maxPageSize = 200;

    /** {@code odata.filter.default-page-size}: page size used when $top is omitted. */
    private int defaultPageSize = 20;

    /** {@code odata.filter.max-nested-property-depth}: max '/'-separated segments in a property path. */
    private int maxNestedPropertyDepth = 2;

    /** {@code odata.filter.max-expression-length}: max raw length of $filter, rejected before parsing. */
    private int maxExpressionLength = 2048;

    /** What to do when a caller's $top exceeds {@link #maxPageSize}. */
    private PageSizeExceededStrategy pageSizeExceededStrategy = PageSizeExceededStrategy.REJECT;

    @NestedConfigurationProperty
    private final Web web = new Web();

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

    public enum PageSizeExceededStrategy {
        /** Reject the request with {@link ru.ludwigandreas.odatafilter.exception.PageSizeExceededException}. */
        REJECT,
        /** Silently cap $top at the configured maximum. */
        CLAMP
    }

    public static class Web {

        /** Whether to register the {@code ODataQuery<T>} Spring MVC argument resolver. */
        private boolean argumentResolverEnabled = true;

        /** Whether to register the {@code @RestControllerAdvice} that maps our exceptions to {@code ProblemDetail}. */
        private boolean problemDetailAdviceEnabled = true;

        /** If true, only {@code $filter}/{@code $top}/... are read; the non-prefixed aliases are ignored. */
        private boolean dollarPrefixedParametersOnly = false;

        public boolean isArgumentResolverEnabled() {
            return argumentResolverEnabled;
        }

        public void setArgumentResolverEnabled(boolean argumentResolverEnabled) {
            this.argumentResolverEnabled = argumentResolverEnabled;
        }

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
}
