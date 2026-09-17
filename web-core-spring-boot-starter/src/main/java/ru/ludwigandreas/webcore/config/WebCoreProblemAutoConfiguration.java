package ru.ludwigandreas.webcore.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.problem.mapper.ConstraintViolationProblemMapper;
import ru.ludwigandreas.webcore.problem.mapper.DataAccessProblemMapper;
import ru.ludwigandreas.webcore.problem.mapper.LocalizedExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.mapper.SecurityProblemMapper;
import ru.ludwigandreas.webcore.trace.MdcTraceIdProvider;
import ru.ludwigandreas.webcore.trace.TraceIdProvider;

/**
 * Wires the problem pipeline: the message chain, the mapper chain, the renderer and the one advice.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}, and the two chains are assembled from
 * {@code ObjectProvider}s rather than from a fixed list. That is the whole extension story: a module
 * or an application adds a {@link ProblemMessageBundle} or an {@link ExceptionProblemMapper} bean and
 * it is picked up, with precedence decided by {@code Ordered} rather than by which starter happened
 * to be autoconfigured first.
 *
 * <p>The mappers this starter registers itself are each conditional on the class they map, so the
 * starter works in a service with no Spring Security, no Bean Validation and no data access without
 * any of them having to be excluded.
 *
 * <p>Everything here is transport-independent: the advice, the framework mappers and the locale
 * resolver live in {@link WebCoreWebMvcAutoConfiguration} and
 * {@link WebCoreLocalizationAutoConfiguration}, so a non-web module using {@code LocalizedException}
 * and {@code ProblemMessages} - a Kafka consumer rendering the same errors into a reply message -
 * gets this pipeline without Spring MVC on its classpath.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.web", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(WebCoreProperties.class)
public class WebCoreProblemAutoConfiguration {

    /**
     * The message chain. Takes the application's {@code MessageSource} if it has one - resolution
     * prefers it, so any module's wording can be overridden by defining the same key locally.
     */
    @Bean
    @ConditionalOnMissingBean
    public ProblemMessages ludwigProblemMessages(
            ObjectProvider<MessageSource> messageSource, ObjectProvider<ProblemMessageBundle> bundles) {
        return new ProblemMessages(messageSource.getIfAvailable(), bundles.orderedStream().toList());
    }

    /** This starter's own bundle, contributed through the same SPI every other module uses. */
    @Bean
    public ProblemMessageBundle ludwigWebProblemMessageBundle() {
        // Lowest precedence among contributed bundles: a module's text for its own errors should win
        // over the generic wording here, and the application's own bundle wins over both.
        return ProblemMessageBundle.of("i18n/ludwig-web-messages", Integer.MAX_VALUE - 1);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ludwig.web.problem", name = "include-trace-id", matchIfMissing = true)
    public TraceIdProvider ludwigTraceIdProvider(WebCoreProperties properties) {
        return new MdcTraceIdProvider(properties.getProblem().getTraceIdMdcKey());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailFactory ludwigProblemDetailFactory(
            ProblemMessages messages,
            ObjectProvider<TraceIdProvider> traceIdProvider,
            WebCoreProperties properties) {
        return new ProblemDetailFactory(
                messages, traceIdProvider.getIfAvailable(TraceIdProvider::none), properties.getProblem());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemMapperRegistry ludwigProblemMapperRegistry(ObjectProvider<ExceptionProblemMapper> mappers) {
        return new ProblemMapperRegistry(mappers.orderedStream().toList());
    }

    /** A service's own declared failures. Highest precedence - nothing reinterprets them. */
    @Bean
    public ExceptionProblemMapper ludwigLocalizedExceptionProblemMapper() {
        return new LocalizedExceptionProblemMapper();
    }

    /**
     * The generic rendering of a Spring Security failure, for services that use Spring Security
     * without this repository's security starter.
     *
     * <p>Named {@code ludwigWebSecurityProblemMapper} rather than {@code ludwigSecurityProblemMapper}
     * so it cannot collide with the bean {@code security-spring-boot-starter} registers. Both are
     * meant to coexist - see {@link SecurityProblemMapper}, which is registered at
     * {@code DEFAULT_MODULE_ORDER} precisely so that a security module contributing a lower-ordered
     * mapper wins - but a shared bean <em>name</em> makes Spring reject the second registration
     * before any of that ordering can apply, and an application with both starters fails to start.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
    public ExceptionProblemMapper ludwigWebSecurityProblemMapper() {
        return new SecurityProblemMapper();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.dao.DataIntegrityViolationException")
    public ExceptionProblemMapper ludwigDataAccessProblemMapper() {
        return new DataAccessProblemMapper();
    }

    @Bean
    @ConditionalOnClass(name = "jakarta.validation.ConstraintViolationException")
    public ExceptionProblemMapper ludwigConstraintViolationProblemMapper() {
        return new ConstraintViolationProblemMapper();
    }

}
