package ru.ludwigandreas.odatafilter.config;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.web.ODataFilterExceptionHandler;
import ru.ludwigandreas.odatafilter.web.ODataQueryArgumentResolver;

/**
 * Registers the Spring MVC integration: the {@code ODataQuery<T>} argument resolver and the
 * {@code ProblemDetail} exception advice. Only activates in a servlet web application with
 * {@code spring-webmvc} on the classpath, so pulling this starter into a non-web module (e.g. a
 * batch job that only needs {@link ODataFilterService} directly) never drags Spring MVC in.
 */
@AutoConfiguration(after = ODataFilterAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
public class ODataFilterWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "odata.filter.web", name = "argument-resolver-enabled", havingValue = "true", matchIfMissing = true)
    public ODataQueryArgumentResolver odataQueryArgumentResolver(
            ODataFilterService filterService, ODataFilterProperties properties) {
        return new ODataQueryArgumentResolver(filterService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "odata.filter.web", name = "problem-detail-advice-enabled", havingValue = "true", matchIfMissing = true)
    public ODataFilterExceptionHandler odataFilterExceptionHandler() {
        return new ODataFilterExceptionHandler();
    }

    @Bean
    @ConditionalOnBean(ODataQueryArgumentResolver.class)
    public WebMvcConfigurer odataFilterWebMvcConfigurer(ODataQueryArgumentResolver resolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(resolver);
            }
        };
    }
}
