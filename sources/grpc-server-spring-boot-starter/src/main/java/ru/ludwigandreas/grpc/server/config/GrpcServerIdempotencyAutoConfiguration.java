package ru.ludwigandreas.grpc.server.config;

import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.grpc.server.interceptor.GrpcProblemRenderer;
import ru.ludwigandreas.grpc.server.interceptor.IdempotentReplayInterceptor;
import ru.ludwigandreas.idempotency.api.Idempotent;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;

/**
 * Idempotent replay, when the platform's idempotency module is on the classpath.
 *
 * <p>The interceptor is registered whether or not a store exists, on purpose: it is what notices a
 * method that carries {@code @Idempotent} with nothing to honour it, and fails startup naming the
 * method. Left out, such a method would simply run twice.
 */
@AutoConfiguration
@ConditionalOnClass({IdempotencyStore.class, Idempotent.class})
@ConditionalOnProperty(prefix = GrpcServerProperties.PREFIX, name = "enabled", matchIfMissing = true)
public class GrpcServerIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotentReplayInterceptor ludwigGrpcIdempotentReplayInterceptor(
            ListableBeanFactory beans, ObjectProvider<IdempotencyStore> stores,
            ObjectProvider<RequestFingerprint> fingerprints, ObjectProvider<IdempotencyProperties> properties,
            ObjectProvider<IdempotencyMetrics> metrics, GrpcProblemRenderer renderer) {
        return new IdempotentReplayInterceptor(beans, stores, fingerprints, properties, metrics, renderer);
    }
}
