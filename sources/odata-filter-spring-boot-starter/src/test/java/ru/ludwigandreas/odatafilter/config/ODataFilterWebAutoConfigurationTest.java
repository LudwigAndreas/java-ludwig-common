package ru.ludwigandreas.odatafilter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.web.ODataFilterProblemMapper;
import ru.ludwigandreas.odatafilter.web.ODataQueryOptionsArgumentResolver;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * What this module registers in a servlet application: its contribution to the shared problem pipeline
 * and the options resolver.
 */
class ODataFilterWebAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ODataFilterMetricsAutoConfiguration.class,
                    ODataFilterAutoConfiguration.class,
                    ODataFilterWebAutoConfiguration.class));

    @Test
    @DisplayName("it contributes a mapper and a bundle to the shared pipeline, and no advice")
    void contributesToTheSharedPipeline() {
        runner.run(context -> {
            assertThat(context)
                    .hasSingleBean(ODataFilterProblemMapper.class)
                    .hasSingleBean(ProblemMessageBundle.class);
            // A second advice for the same exception types is how one failure ends up answered
            // differently depending on which bean won the ordering.
            assertThat(context.getBeansWithAnnotation(RestControllerAdvice.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("the options resolver is registered unconditionally, unlike the one it replaced")
    void optionsResolverIsAlwaysRegistered() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(ODataQueryOptionsArgumentResolver.class)
                .hasSingleBean(WebMvcConfigurer.class));
    }

    @Test
    @DisplayName("an application can replace the options resolver with one of its own")
    void optionsResolverIsReplaceable() {
        runner.withBean(ODataQueryOptionsArgumentResolver.class, () -> new ODataQueryOptionsArgumentResolver(
                        new ODataFilterProperties()))
                .run(context -> assertThat(context).hasSingleBean(ODataQueryOptionsArgumentResolver.class));
    }
}
