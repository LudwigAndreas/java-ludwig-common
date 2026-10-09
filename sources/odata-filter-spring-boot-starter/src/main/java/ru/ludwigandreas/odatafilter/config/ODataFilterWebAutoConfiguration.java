package ru.ludwigandreas.odatafilter.config;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.web.ODataFilterExceptionHandler;
import ru.ludwigandreas.odatafilter.web.ODataFilterProblemMapper;
import ru.ludwigandreas.odatafilter.web.ODataQueryOptionsArgumentResolver;
import ru.ludwigandreas.odatafilter.web.ODataQueryOptionsOpenApiCustomizer;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * Registers this module's contribution to the application's error responses and the
 * {@link ODataQueryOptions} argument resolver. Only activates in a servlet web application with
 * {@code spring-webmvc} on the classpath, so pulling this starter into a non-web module (e.g. a batch
 * job that only needs {@link ODataFilterService} directly) never drags Spring MVC in.
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

    /**
     * Lets a controller take the five OData query options as one parameter.
     *
     * <p>Registered unconditionally, unlike the deprecated resolver it replaces, which was off by
     * default because it could not be used without breaking the platform's layering. See
     * {@link ODataQueryOptionsArgumentResolver} for why each of those objections applies to what that
     * resolver produced and not to this one.
     */
    @Bean
    @ConditionalOnMissingBean
    public ODataQueryOptionsArgumentResolver odataQueryOptionsArgumentResolver(ODataFilterProperties properties) {
        return new ODataQueryOptionsArgumentResolver(properties);
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

    /** Installs the options resolver into Spring MVC. */
    @Bean
    public WebMvcConfigurer odataFilterWebMvcConfigurer(ODataQueryOptionsArgumentResolver resolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(resolver);
            }
        };
    }

    /**
     * Describes the {@link ODataQueryOptions} parameter in the application's OpenAPI document.
     *
     * <p><b>Why this is a nested class and not a {@code @Bean} method with
     * {@code @ConditionalOnClass} on it.</b> A conditional on a bean method cannot protect a method
     * whose <em>return type</em> is the thing that might be missing: Spring resolves the method
     * signature while parsing the configuration class, before the condition is evaluated, and
     * {@code ODataQueryOptionsOpenApiCustomizer} implements springdoc's {@code OperationCustomizer}.
     * In a service with springdoc absent - which is most of them, because the dependency is optional -
     * that threw {@code NoClassDefFoundError: org/springdoc/core/customizers/OperationCustomizer} and
     * failed the whole application context, not just this bean. A nested configuration class is
     * skipped by ASM without any type being loaded.
     *
     * <p>Conditional on the class rather than on a property: if springdoc is on the classpath the
     * application has an OpenAPI document, and leaving the five query options out of it is never the
     * right answer.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springdoc.core.customizers.OperationCustomizer")
    public static class SpringdocConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ODataQueryOptionsOpenApiCustomizer odataQueryOptionsOpenApiCustomizer() {
            return new ODataQueryOptionsOpenApiCustomizer();
        }
    }
}
