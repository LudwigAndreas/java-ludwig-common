package ru.ludwigandreas.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import ru.ludwigandreas.security.audit.AccessAuditLogger;
import ru.ludwigandreas.security.audit.Slf4jAccessAuditLogger;
import ru.ludwigandreas.security.authz.AuthorityCache;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.CachingAuthorityResolver;
import ru.ludwigandreas.security.authz.CaffeineAuthorityCache;
import ru.ludwigandreas.security.authz.NoopAuthorityCache;
import ru.ludwigandreas.security.authz.TokenClaimAuthorityResolver;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.SystemPrincipalTemplate;
import ru.ludwigandreas.security.web.ProblemDetailAccessDeniedHandler;
import ru.ludwigandreas.security.web.ProblemDetailAuthenticationEntryPoint;
import ru.ludwigandreas.security.web.SecurityMessages;

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

    @Bean
    @ConditionalOnMissingBean(AuthorityCache.class)
    @ConditionalOnClass(Caffeine.class)
    @ConditionalOnProperty(prefix = "ludwig.security.authorities.cache", name = "enabled",
            matchIfMissing = true)
    public AuthorityCache ludwigAuthorityCache(SecurityProperties properties) {
        SecurityProperties.Cache cache = properties.getAuthorities().getCache();
        return new CaffeineAuthorityCache(cache.getTtl(), cache.getMaximumSize());
    }

    @Bean
    @ConditionalOnMissingBean(AuthorityCache.class)
    public AuthorityCache ludwigNoopAuthorityCache() {
        return new NoopAuthorityCache();
    }

    /**
     * Wraps whichever {@link AuthorityResolver} won with the cache and metrics. Declared as
     * {@link AuthorityLookup} - a different type from the SPI - so this bean cannot end up competing
     * with the resolver it decorates for the same injection point.
     */
    @Bean
    @ConditionalOnMissingBean(AuthorityLookup.class)
    public AuthorityLookup ludwigAuthorityLookup(AuthorityResolver resolver, AuthorityCache cache,
                                                 SecurityMetrics metrics) {
        return new CachingAuthorityResolver(resolver, cache, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(AccessAuditLogger.class)
    @ConditionalOnProperty(prefix = "ludwig.security.audit", name = "enabled", matchIfMissing = true)
    public AccessAuditLogger ludwigAccessAuditLogger(SecurityProperties properties) {
        return new Slf4jAccessAuditLogger(properties.getAudit().isLogGrants());
    }

    /** Audit disabled: still needs a bean, because the guard records unconditionally. */
    @Bean
    @ConditionalOnMissingBean(AccessAuditLogger.class)
    public AccessAuditLogger ludwigNoopAccessAuditLogger() {
        return decision -> { };
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
