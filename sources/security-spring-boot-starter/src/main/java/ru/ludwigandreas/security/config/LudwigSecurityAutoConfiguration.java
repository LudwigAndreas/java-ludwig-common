package ru.ludwigandreas.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityCaches;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.CachingAuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.authz.TokenClaimAuthorityResolver;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.SystemPrincipalTemplate;
import ru.ludwigandreas.security.web.ProblemDetailAccessDeniedHandler;
import ru.ludwigandreas.security.web.ProblemDetailAuthenticationEntryPoint;
import ru.ludwigandreas.security.web.SecurityMessages;
import ru.ludwigandreas.security.web.SecurityProblemMapper;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The module's shared beans: identity-to-authority resolution, its cache, the audit sink and the two
 * RFC 7807 error renderers.
 *
 * <p>Split from the filter-chain configuration so a service can use the authorization half of this
 * module - guards, scopes, audit - while owning its own {@code SecurityFilterChain}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.security", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SecurityProperties.class)
public class LudwigSecurityAutoConfiguration {

    /**
     * The fallback resolver, registered only when nothing else provides one. It grants nothing, which
     * makes a service that forgot to add {@code identity-projection-spring-boot-starter} fail visibly
     * (every request 403s) instead of running with some implicit default.
     */
    @Bean
    @ConditionalOnMissingBean(AuthorityResolver.class)
    public AuthorityResolver ludwigFallbackAuthorityResolver(SecurityProperties properties) {
        if (properties.getAuthorities().isRequireResolver()) {
            throw new SecurityConfigurationException(
                    "ludwig.security.authorities.require-resolver=true but no AuthorityResolver bean is "
                            + "registered. Add identity-projection-spring-boot-starter, or register your "
                            + "own AuthorityResolver. Starting without one would leave every caller with "
                            + "no roles at all.");
        }
        return new TokenClaimAuthorityResolver();
    }

    /**
     * This module's declaration of the platform's {@code authorities} cache.
     *
     * <p>A plain data bean with no dependency on the cache registry, which is what lets the registry collect
     * every declaration on the classpath and validate the complete set before any cache is built from it.
     *
     * <p>What it declares that no configuration could is the <em>meaning</em> of the TTL: this one is a
     * revocation window, not a throughput knob. See {@link AuthorityCaches}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigAuthorityCacheDefinition")
    public CacheDefinition<PrincipalRef, Authorities> ludwigAuthorityCacheDefinition() {
        return AuthorityCaches.definition();
    }

    /**
     * The cache itself, resolved from the registry.
     *
     * <p>Published as a bean rather than resolved at each call site so that
     * {@code identity-projection-spring-boot-starter}, which evicts a principal the moment a Kafka event
     * changes their roles, injects the same instance the filters read through.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigAuthorityCache")
    public LudwigCache<PrincipalRef, Authorities> ludwigAuthorityCache(
            LudwigCacheRegistry registry, CacheDefinition<PrincipalRef, Authorities> definition) {
        return registry.cache(definition);
    }

    /**
     * Wraps whichever {@link AuthorityResolver} won with the cache and metrics. Declared as
     * {@link AuthorityLookup} - a different type from the SPI - so this bean cannot end up competing
     * with the resolver it decorates for the same injection point.
     */
    @Bean
    @ConditionalOnMissingBean(AuthorityLookup.class)
    public AuthorityLookup ludwigAuthorityLookup(AuthorityResolver resolver,
                                                 LudwigCache<PrincipalRef, Authorities> cache,
                                                 SecurityMetrics metrics) {
        return new CachingAuthorityResolver(resolver, cache, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityMessages.class)
    public SecurityMessages ludwigSecurityMessages(ObjectProvider<MessageSource> messageSource) {
        return new SecurityMessages(messageSource.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticationEntryPoint.class)
    public AuthenticationEntryPoint ludwigAuthenticationEntryPoint(ObjectProvider<ObjectMapper> objectMapper,
                                                                   SecurityMessages messages,
                                                                   SecurityProperties properties) {
        return new ProblemDetailAuthenticationEntryPoint(objectMapper.getIfAvailable(ObjectMapper::new),
                messages, properties.getProblemTypePrefix());
    }

    @Bean
    @ConditionalOnMissingBean(AccessDeniedHandler.class)
    public AccessDeniedHandler ludwigAccessDeniedHandler(ObjectProvider<ObjectMapper> objectMapper,
                                                          SecurityMessages messages,
                                                          SecurityProperties properties) {
        return new ProblemDetailAccessDeniedHandler(objectMapper.getIfAvailable(ObjectMapper::new),
                messages, properties.getProblemTypePrefix());
    }

    /**
     * This module's error text, contributed to the shared problem pipeline.
     *
     * <p>{@link SecurityMessages} keeps resolving the same bundle for the two filter-chain handlers,
     * which have to write a response without an MVC dispatch and so cannot use that pipeline.
     * Contributing the bundle as well is what makes both paths - a rejection in the filter chain and
     * one from a {@code @PreAuthorize} - produce the same text for the same code.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigSecurityProblemMessageBundle")
    @ConditionalOnClass(ProblemMessageBundle.class)
    public ProblemMessageBundle ludwigSecurityProblemMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-security-messages");
    }

    /** Keeps an in-dispatch 401/403 under the same codes the filter-chain handlers use. */
    @Bean
    @ConditionalOnMissingBean(SecurityProblemMapper.class)
    @ConditionalOnClass(ExceptionProblemMapper.class)
    public SecurityProblemMapper ludwigSecurityProblemMapper() {
        return new SecurityProblemMapper();
    }

    @Bean
    @ConditionalOnMissingBean(SystemPrincipalTemplate.class)
    public SystemPrincipalTemplate ludwigSystemPrincipalTemplate(SecurityProperties properties) {
        SecurityProperties.SystemPrincipal system = properties.getSystemPrincipal();
        return new SystemPrincipalTemplate(system.getSubject(),
                Set.copyOf(system.getRoles().stream()
                        .map(ru.ludwigandreas.security.authz.Authorities::normalizeRole)
                        .toList()));
    }
}
