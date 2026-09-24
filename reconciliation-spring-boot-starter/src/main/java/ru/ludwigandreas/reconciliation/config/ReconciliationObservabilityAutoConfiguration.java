package ru.ludwigandreas.reconciliation.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.reconciliation.engine.CorrelationIdSource;

import java.util.UUID;

/**
 * Binds each run's correlation id through {@code observability-spring-boot-starter}, when it is
 * present.
 *
 * <p>This is what makes one partner interaction traceable end to end: the id bound here reaches the
 * outbound calls the run's fetcher makes - the observability starter propagates it - and is stamped
 * onto every staged row and audit event the run produces. Without the starter the module still stamps
 * an id, so its own trail stays joinable; what is lost is everything outside it.
 *
 * <p>Isolated in its own configuration class so that the reference to {@code CorrelationContext}
 * exists only where {@code @ConditionalOnClass} has already established that the type does.
 */
@AutoConfiguration
@ConditionalOnClass(CorrelationContext.class)
@AutoConfigureBefore(ReconciliationAutoConfiguration.class)
public class ReconciliationObservabilityAutoConfiguration {

    /**
     * The observability-backed correlation source.
     *
     * @param context the application's correlation context
     * @return the source
     */
    @Bean
    @ConditionalOnBean(CorrelationContext.class)
    @ConditionalOnMissingBean(CorrelationIdSource.class)
    public CorrelationIdSource reconciliationCorrelationIdSource(CorrelationContext context) {
        return CorrelationIdSource.of(() -> UUID.randomUUID().toString(), id -> {
            CorrelationContext.Scope scope = context.open(id);
            return scope::close;
        });
    }
}
