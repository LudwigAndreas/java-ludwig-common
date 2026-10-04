package ru.ludwigandreas.pat.autoconfigure;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import ru.ludwigandreas.pat.config.PatExchangeConfigurationValidator;
import ru.ludwigandreas.pat.config.PatProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.pat.cache.CachedVerification;
import ru.ludwigandreas.pat.cache.PatCaches;
import ru.ludwigandreas.pat.exchange.ExchangeRateLimiter;
import ru.ludwigandreas.pat.exchange.JoseAssertionMinter;
import ru.ludwigandreas.pat.exchange.PatAssertionMinter;
import ru.ludwigandreas.pat.exchange.PatVerifier;
import ru.ludwigandreas.pat.exchange.TokenExchangeController;
import ru.ludwigandreas.pat.metrics.PatMetrics;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.service.PatUsageTracker;

/**
 * The exchange endpoint, its verifier, its limiter and its minter.
 *
 * <p>Separate from {@link PatWebAutoConfiguration} so a deployment can have the management API without the
 * exchange or the exchange without the management API. Those are genuinely different decisions: a service
 * might issue tokens for an operator UI while a dedicated edge-facing deployment does the exchanging.
 */
@AutoConfiguration(after = PatAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RSAKey.class)
@ConditionalOnBean({PatQueryRepository.class, AuditSink.class})
@ConditionalOnProperty(prefix = "ludwig.pat.exchange", name = "enabled", matchIfMissing = true)
public class PatExchangeAutoConfiguration {

    /**
     * Fails startup when the exchange is enabled without an issuer.
     *
     * <p>Same mechanism and same reasoning as the security module's audience check: an assertion minted with
     * no issuer is an assertion no resource server can validate the provenance of, and a deployment that
     * discovered that at the first exchange would discover it as "tokens do not work" rather than as a
     * configuration error. Refusing to start names the property instead.
     *
     * <p>A missing <em>audience list</em> is deliberately a warning rather than a failure. An empty list
     * means "mint for any audience the token itself permits", which is a coherent and reasonably safe
     * posture - the token's own audience set still gates it. It is logged because a deployment that meant to
     * restrict and left the list empty has no other signal.
     */
    @Bean
    public PatExchangeConfigurationValidator ludwigPatExchangeConfigurationValidator(
            PatProperties properties,
            ru.ludwigandreas.security.config.SecurityProperties securityProperties) {
        PatExchangeConfigurationValidator validator = new PatExchangeConfigurationValidator(
                properties.getExchange(), securityProperties.getPublicPaths());
        validator.validate();
        return validator;
    }

    /**
     * The verification cache, resolved from the registry rather than built here.
     *
     * <p>Resolving it from {@link LudwigCacheRegistry} is what applies the platform's governance to it: the
     * security-purpose TTL ceiling, the refusal to serve stale, the recorded statistics. A Caffeine builder
     * here would have none of that and {@code RuleGroup.CACHING} would fail the build on it.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigPatVerificationCache")
    public LudwigCache<String, CachedVerification> ludwigPatVerificationCache(
            LudwigCacheRegistry registry) {
        return registry.cache(PatCaches.definition());
    }

    @Bean
    @ConditionalOnMissingBean
    public PatVerifier ludwigPatVerifier(PatQueryRepository queries,
                                          LudwigCache<String, CachedVerification> cache,
                                          PatUsageTracker usageTracker,
                                          AuditSink auditSink,
                                          Clock clock) {
        return new PatVerifier(queries, cache, usageTracker, auditSink, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExchangeRateLimiter ludwigPatExchangeRateLimiter(PatProperties properties, Clock clock) {
        return new ExchangeRateLimiter(properties.getRateLimit(), clock);
    }

    /**
     * The default minter, used only when the deployment supplies no other and supplies a signing key.
     *
     * <p>Conditional on an {@link RSAKey} bean rather than generating one. A generated key would make the
     * module start successfully and mint assertions that nothing can validate - and worse, a key regenerated
     * on restart would invalidate every assertion in flight, which presents as intermittent authentication
     * failures rather than as a missing configuration.
     */
    @Bean
    @ConditionalOnMissingBean(PatAssertionMinter.class)
    @ConditionalOnBean(RSAKey.class)
    public PatAssertionMinter ludwigPatAssertionMinter(RSAKey signingKey, PatProperties properties,
                                                        Clock clock) {
        return new JoseAssertionMinter(signingKey, properties.getExchange().getIssuer(), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PatAssertionMinter.class)
    public TokenExchangeController ludwigTokenExchangeController(PatVerifier verifier,
                                                                  PatAssertionMinter minter,
                                                                  ExchangeRateLimiter rateLimiter,
                                                                  PatProperties properties,
                                                                  PatMetrics metrics) {
        return new TokenExchangeController(verifier, minter, rateLimiter, properties, metrics);
    }

}
