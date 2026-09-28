package ru.ludwigandreas.cache.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import ru.ludwigandreas.cache.metrics.CacheMetrics;
import ru.ludwigandreas.cache.shared.RedisSharedCacheTier;
import ru.ludwigandreas.cache.shared.SharedCacheTier;

/**
 * The shared tier, registered only when a deployment has asked for it and Redis is actually there.
 *
 * <p>Three conditions, and each rules out a different failure: {@code spring-data-redis} on the classpath,
 * a {@code RedisConnectionFactory} on the context (so that a service which merely has the jar does not
 * start a subscriber), and {@code ludwig.cache.tiers.shared.enabled=true}.
 *
 * <p>A cache that lists {@code shared} while this configuration did not apply fails at startup with a
 * message naming the missing piece, from the registry. That is better than the alternative of quietly
 * running local-only: the whole reason somebody listed the shared tier is that they expected the other
 * replicas to see their entries.
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({StringRedisTemplate.class, RedisMessageListenerContainer.class})
@ConditionalOnBean(RedisConnectionFactory.class)
@ConditionalOnProperty(prefix = CacheProperties.PREFIX, name = "tiers.shared.enabled",
        havingValue = "true")
public class LudwigCacheRedisAutoConfiguration {

    /**
     * The container the per-cache invalidation subscriptions are registered on.
     *
     * <p>A container of this module's own rather than a shared one, so that a slow or failing cache listener
     * cannot delay a listener some other part of the service registered, and so that closing the context
     * stops these subscriptions without depending on who else was using it.
     */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnMissingBean(name = "ludwigCacheInvalidationListenerContainer")
    public RedisMessageListenerContainer ludwigCacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * The shared tier.
     *
     * <p>{@code StringRedisTemplate} is built here rather than injected: the envelope is JSON this module
     * writes itself, and a {@code RedisSerializer} the consuming service configured on its own template must
     * not be able to change what a cached entry looks like on the wire - two releases disagreeing about the
     * serializer is precisely the silent-corruption case the key namespace version exists to prevent.
     */
    @Bean
    @ConditionalOnMissingBean(SharedCacheTier.class)
    public SharedCacheTier ludwigSharedCacheTier(RedisConnectionFactory connectionFactory,
                                                 RedisMessageListenerContainer container,
                                                 ObjectProvider<ObjectMapper> objectMapper,
                                                 CacheMetrics metrics,
                                                 ObjectProvider<Clock> clock,
                                                 Environment environment) {
        return new RedisSharedCacheTier(new StringRedisTemplate(connectionFactory),
                objectMapper.getIfAvailable(ObjectMapper::new), container, metrics,
                clock.getIfAvailable(Clock::systemUTC),
                environment.getProperty("spring.application.name", ""));
    }
}
