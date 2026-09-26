package ru.ludwigandreas.idempotency.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.idempotency.store.RedisIdempotencyStore;

/**
 * The Redis backend, when a deployment has explicitly chosen it.
 *
 * <p>Chosen with {@code ludwig.idempotency.backend=redis} and never by the mere presence of a
 * {@code StringRedisTemplate}: a service that uses Redis as a cache has one, and inferring a store from it
 * would silently move work-dedup onto a backend that cannot serve the consumer case.
 *
 * <p><b>Read {@code RedisIdempotencyStore}'s class documentation before configuring this.</b> A Redis claim
 * is not transactional with the database write it protects, so it can serve
 * {@code ClaimMode.STANDALONE} only, and a crash between the claim and the commit leaves a key claimed for
 * work that never happened. That is acceptable for an HTTP filter in front of an idempotent handler and is
 * not acceptable for a consumer.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "ludwig.idempotency", name = "backend", havingValue = "redis")
@AutoConfigureAfter(IdempotencyAutoConfiguration.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyRedisAutoConfiguration {

    /**
     * The Redis store.
     *
     * @param redis        the template
     * @param objectMapper serialises the stored claim
     * @param metrics      what this module reports about itself
     * @param properties   the configuration
     * @param clock        the clock claims are judged against
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    public RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redis,
                                                       ObjectProvider<ObjectMapper> objectMapper,
                                                       IdempotencyMetrics metrics,
                                                       IdempotencyProperties properties,
                                                       ObjectProvider<Clock> clock) {
        return new RedisIdempotencyStore(redis, objectMapper.getIfAvailable(ObjectMapper::new), metrics,
                IdempotencyAutoConfiguration.clockOf(clock), properties.getRedis().getKeyPrefix());
    }
}
