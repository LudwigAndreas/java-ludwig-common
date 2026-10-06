package ru.ludwigandreas.pat.autoconfigure;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import ru.ludwigandreas.pat.config.PatProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.pat.cache.PatCaches;
import ru.ludwigandreas.pat.metrics.NoopPatMetrics;
import ru.ludwigandreas.pat.metrics.PatMetrics;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.repository.PatQueryRepositoryImpl;
import ru.ludwigandreas.pat.repository.PatRepository;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.PatUsageTracker;

/**
 * The issuer's beans: storage, the lifecycle service, the cache declaration and the usage tracker.
 *
 * <p>Conditional on an {@link AuditSink} being present rather than defaulting to a no-op one. Issuance,
 * rotation and revocation are each events a deployment is accountable for, and a credential subsystem that
 * silently audited nothing because a bean was missing would be worse than one that refuses to start - the
 * second is a ten-minute configuration fix, the first is discovered during an investigation that has no
 * records.
 */
@AutoConfiguration
@ConditionalOnClass({EntityManager.class, PatRepository.class})
@ConditionalOnBean(AuditSink.class)
@ConditionalOnProperty(prefix = "ludwig.pat", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(PatProperties.class)
@EntityScan(basePackageClasses = ru.ludwigandreas.pat.entity.PatEntity.class)
@EnableJpaRepositories(basePackageClasses = PatRepository.class)
public class PatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PatQueryRepository ludwigPatQueryRepository(EntityManager entityManager) {
        return new PatQueryRepositoryImpl(entityManager);
    }

    /**
     * The verification cache's declaration.
     *
     * <p>Published as a bean so {@code cache-spring-boot-starter} validates it at startup - which is what
     * applies the security-purpose TTL ceiling to it. A definition resolved lazily at first use would be
     * validated after the deployment had already started serving.
     */
    @Bean
    public CacheDefinition<String, ru.ludwigandreas.pat.cache.CachedVerification> ludwigPatCacheDefinition() {
        return PatCaches.definition();
    }

    @Bean
    @ConditionalOnMissingBean
    public PatMetrics ludwigPatMetrics() {
        return new NoopPatMetrics();
    }

    /**
     * The clock, injected everywhere rather than read statically.
     *
     * <p>Every expiry, overlap and retention decision in this module is a comparison against now, and a
     * static {@code Instant.now()} makes all of them untestable without sleeping. Deliberately
     * {@code Clock.systemUTC()} and not {@code Clock.systemDefaultZone()} - the JVM default is the
     * container's, and {@code RuleGroup.PRESENTATION} fails the build on the latter for exactly that reason.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock ludwigPatClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public PatService ludwigPatService(PatRepository repository, PatQueryRepository queries,
                                       PatProperties properties, AuditSink auditSink, Clock clock) {
        return new PatService(repository, queries, properties, auditSink, clock);
    }

    /**
     * Revokes a disabled owner's tokens when the identity projection says so.
     *
     * <p>Unconditional on the publisher being present: a {@code @EventListener} for an event nobody
     * publishes simply never fires, and making it conditional would mean a deployment that later added the
     * projection had to know to reconfigure this. See the listener for why this is an accountability
     * mechanism rather than the thing that makes a departed owner's tokens stop working.
     */
    @Bean
    @ConditionalOnMissingBean
    public ru.ludwigandreas.pat.service.PatOwnerDisabledListener ludwigPatOwnerDisabledListener(
            PatService service) {
        return new ru.ludwigandreas.pat.service.PatOwnerDisabledListener(service);
    }

    /**
     * The executor the best-effort last-use write runs on.
     *
     * <p>A single daemon thread, bounded by construction. The work is one indexed update per token per
     * debounce interval, so throughput is not the constraint - and a pool that could grow would let a
     * database slowdown turn into unbounded queued work on the hot path's behalf. Daemon so it never holds
     * up a shutdown for a telemetry write.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "ludwigPatUsageExecutor")
    public java.util.concurrent.ExecutorService ludwigPatUsageExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ludwig-pat-usage");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The usage tracker, with its executor injected <b>by name</b>.
     *
     * <p>The qualifier is not decoration. Every Spring Boot web application has an
     * {@code applicationTaskExecutor}, and {@code cache-spring-boot-starter} contributes a
     * {@code ludwigCacheRefreshExecutor} - so an unqualified {@code Executor} parameter here resolves to
     * three candidates and the context fails to start. It failed exactly that way in
     * {@code PatManagementIT} before this qualifier existed, which is worth recording: the defect is
     * invisible in a unit test (which passes the executor directly) and fatal in every real deployment.
     *
     * <p>Named rather than {@code @Primary} on this module's bean, because marking a module's executor
     * primary would change which executor the <em>application's</em> unqualified injections resolve to.
     */
    @Bean
    @ConditionalOnMissingBean
    public PatUsageTracker ludwigPatUsageTracker(
            PatRepository repository, PatProperties properties, AuditSink auditSink, PatMetrics metrics,
            Clock clock,
            @org.springframework.beans.factory.annotation.Qualifier("ludwigPatUsageExecutor")
            Executor executor) {
        return new PatUsageTracker(repository, properties, auditSink, metrics, clock, executor);
    }
}
