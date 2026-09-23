package ru.ludwigandreas.odatafilter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.ludwigandreas.odatafilter.web.ODataFilterExceptionHandler;
import ru.ludwigandreas.odatafilter.web.ODataFilterProblemMapper;
import ru.ludwigandreas.odatafilter.web.ODataQueryArgumentResolver;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * Which of the two error-rendering paths is taken, and - the part worth a test - that the module
 * still starts at all when the web-core starter is absent. It is an optional dependency, so the
 * autoconfiguration has to be loadable by a class loader with no web-core jar at all.
 */
class ODataFilterWebAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ODataFilterMetricsAutoConfiguration.class,
                    ODataFilterAutoConfiguration.class,
                    ODataFilterWebAutoConfiguration.class));

    @Test
    @DisplayName("with web-core present, it contributes to the shared pipeline and ships no advice")
    void contributesToTheSharedPipeline() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(ODataFilterProblemMapper.class)
                .hasSingleBean(ProblemMessageBundle.class)
                // The two must never coexist: a second advice for the same exception types is how
                // one failure ends up answered differently depending on which bean won the ordering.
                .doesNotHaveBean(ODataFilterExceptionHandler.class));
    }

    @Test
    @DisplayName("without web-core, the module still starts and falls back to its own advice")
    void fallsBackToItsOwnAdvice() {
        runner.withClassLoader(new FilteredClassLoader(
                        FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.webcore")))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ODataFilterExceptionHandler.class));
    }

    @Test
    @DisplayName("the deprecated argument resolver is not registered unless it is asked for")
    @SuppressWarnings("deprecation")
    void argumentResolverIsOffByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ODataQueryArgumentResolver.class));
    }

    @Test
    @DisplayName("a service that still wants the argument resolver can switch it back on")
    @SuppressWarnings("deprecation")
    void argumentResolverCanBeOptedBackIn() {
        runner.withPropertyValues("odata.filter.web.argument-resolver-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ODataQueryArgumentResolver.class));
    }

    @Test
    @DisplayName("the legacy advice can still be switched off by the property it always had")
    void legacyAdviceRemainsDisableable() {
        runner.withClassLoader(new FilteredClassLoader(
                        FilteredClassLoader.PackageFilter.of("ru.ludwigandreas.webcore")))
                .withPropertyValues("odata.filter.web.problem-detail-advice-enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ODataFilterExceptionHandler.class));
    }
}
