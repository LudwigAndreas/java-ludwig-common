package ru.ludwigandreas.odatafilter.config;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.web.ODataFilterExceptionHandler;
import ru.ludwigandreas.odatafilter.web.ODataFilterProblemMapper;
import ru.ludwigandreas.odatafilter.web.ODataQueryArgumentResolver;

/**
 * Registers the Spring MVC integration: the {@code ODataQuery<T>} argument resolver and this
 * module's contribution to the application's error responses. Only activates in a servlet web
 * application with {@code spring-webmvc} on the classpath, so pulling this starter into a non-web
 * module (e.g. a batch job that only needs {@link ODataFilterService} directly) never drags Spring
 * MVC in.
 *
 * <h2>How query errors are rendered</h2>
 *
 * <p>Two mutually exclusive paths, chosen by what is on the classpath:
 *
 * <ul>
 *   <li>With {@code web-core-spring-boot-starter} present - the intended arrangement - this module
 *       contributes an {@link ODataFilterProblemMapper} and a message bundle to that starter's
 *       shared problem pipeline. Query errors then come back in the caller's language, in exactly
 *       the same shape as every other error the service emits, and the application can reword any
 *       of them by defining the same key in its own bundle.
 *   <li>Without it, the legacy {@link ODataFilterExceptionHandler} advice is registered instead, so
 *       a service that does not use the web-core starter still gets RFC 7807 responses - in English,
 *       from the exceptions' own developer-facing messages.
 * </ul>
 *
 * <p>The two never coexist: a second advice for the same exception types is how an API ends up
 * answering the same failure differently depending on which bean won the ordering.
 */
@AutoConfiguration(after = ODataFilterAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
public class ODataFilterWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "odata.filter.web", name = "argument-resolver-enabled",
            havingValue = "true", matchIfMissing = true)
    public ODataQueryArgumentResolver odataQueryArgumentResolver(
            ODataFilterService filterService, ODataFilterProperties properties) {
        return new ODataQueryArgumentResolver(filterService, properties);
    }

    /**
     * This module's failures, declared as meanings for the shared problem pipeline to render.
     *
     * <p>Conditional on the class rather than on a property: if web-core is on the classpath, its
     * pipeline is what renders errors, and contributing to it is never the wrong thing to do.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ExceptionProblemMapper.class)
    public ODataFilterProblemMapper odataFilterProblemMapper() {
        return new ODataFilterProblemMapper();
    }

    /** The localized text for the codes {@link ODataFilterProblemMapper} produces. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ProblemMessageBundle.class)
    public ProblemMessageBundle odataFilterProblemMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-odata-filter-messages");
    }

    /**
     * The pre-web-core advice, kept for services that do not use that starter.
     *
     * <p>Stands down when web-core is present, because its pipeline renders these exceptions
     * through the mapper above - localized, and in the same shape as the rest of the API.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnMissingClass("ru.ludwigandreas.webcore.problem.ExceptionProblemMapper")
    @ConditionalOnProperty(
            prefix = "odata.filter.web", name = "problem-detail-advice-enabled",
            havingValue = "true", matchIfMissing = true)
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
