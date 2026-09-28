package ru.ludwigandreas.idempotency.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.entity.IdempotencyClaimEntity;
import ru.ludwigandreas.idempotency.lifecycle.IdempotencyPurgeJob;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.idempotency.repository.IdempotencyClaimRepository;
import ru.ludwigandreas.idempotency.sql.ClaimGateway;
import ru.ludwigandreas.idempotency.store.PostgresIdempotencyStore;
import ru.ludwigandreas.job.core.lock.RunLock;

/**
 * The claim table, the store over it, and the retention purge.
 *
 * <p>{@code @EntityScan} and {@code @EnableJpaRepositories} are scoped to this module's own packages, which
 * is how every persistence-carrying starter here does it: scanning wider from a library is how a starter
 * comes to own the consuming application's entity discovery and then breaks it by adding a package. And
 * {@code repositoryBaseClass} has to be named, because {@code db-core}'s {@code BaseRepository} declares
 * methods - {@code getByIdOrThrow} and friends - that only its own implementation provides; without it
 * Spring Data tries to derive a query from the method name and fails at context start with a message about
 * a property called "throw".
 *
 * <p>Registered only when the configured backend is Postgres. The Redis backend has its own configuration
 * and its own caveat, and a deployment that chose it should not also get a claim table it never writes to.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.idempotency", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(IdempotencyAutoConfiguration.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
@EntityScan(basePackageClasses = IdempotencyClaimEntity.class)
@EnableJpaRepositories(basePackageClasses = IdempotencyClaimRepository.class,
        repositoryBaseClass = BaseRepositoryImpl.class)
public class IdempotencyPersistenceAutoConfiguration {

    /** Bean name of the scheduler the purge registers itself with. */
    public static final String PURGE_SCHEDULER = "idempotencyTaskScheduler";

    /**
     * Runs the conditional upsert on whatever connection the caller's transaction is bound to.
     *
     * @param dataSource   the application's data source
     * @param objectMapper reads a stored response's headers back
     * @return the gateway
     */
    @Bean
    @ConditionalOnMissingBean
    public ClaimGateway idempotencyClaimGateway(DataSource dataSource,
                                                ObjectProvider<ObjectMapper> objectMapper) {
        return new ClaimGateway(dataSource, objectMapper.getIfAvailable(ObjectMapper::new));
    }

    /**
     * The store.
     *
     * <p>Conditional on the backend, and on there being no store already: a service with a genuine reason
     * to implement {@link IdempotencyStore} itself - a test double, a store over a system this platform
     * does not know about - replaces this one by declaring a bean, with no property to set.
     *
     * @param gateway            the conditional upsert
     * @param repository         the reads, the transitions and the purge
     * @param transactionManager the manager the store's two transaction boundaries are declared over
     * @param objectMapper       serialises a stored response's headers
     * @param metrics            what this module reports about itself
     * @param clock              the clock claims are judged against
     * @return the store
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnProperty(prefix = "ludwig.idempotency", name = "backend", havingValue = "postgres",
            matchIfMissing = true)
    public PostgresIdempotencyStore postgresIdempotencyStore(ClaimGateway gateway,
                                                             IdempotencyClaimRepository repository,
                                                             PlatformTransactionManager transactionManager,
                                                             ObjectProvider<ObjectMapper> objectMapper,
                                                             IdempotencyMetrics metrics,
                                                             ObjectProvider<Clock> clock) {
        return new PostgresIdempotencyStore(gateway, repository, transactionManager,
                objectMapper.getIfAvailable(ObjectMapper::new), metrics,
                IdempotencyAutoConfiguration.clockOf(clock));
    }

    /**
     * The scheduler the purge registers itself with.
     *
     * <p>Its own, not the application's. A purge queued behind somebody else's scheduled job would hold
     * the distributed lock's lease while waiting for a thread, which is how one slow job stops an
     * unrelated one - and a library that took over the application's scheduler would be deciding its
     * thread budget.
     *
     * @return the scheduler
     */
    @Bean(name = PURGE_SCHEDULER, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = PURGE_SCHEDULER)
    public TaskScheduler idempotencyTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ludwig-idem-purge-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * The retention purge, under {@code job-core}'s leased lock so one instance runs it at a time.
     *
     * <p>Conditional on the lock rather than on a property, because there is no correct way to run this
     * without one: three replicas purging concurrently contend on the hot path of the busiest table in the
     * service. A deployment with no {@code RunLock} gets no purge and the claim table grows, which is
     * visible in the warning the job would otherwise have logged - so the README says to wire the lock.
     *
     * @param idempotencyTaskScheduler the module's own scheduler
     * @param store         the store whose claims are purged
     * @param lock          the platform's one distributed lock
     * @param properties    the configuration
     * @param metrics       what this module reports about itself
     * @param clock         the clock windows are judged against
     * @return the job
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({RunLock.class, PostgresIdempotencyStore.class})
    @ConditionalOnProperty(prefix = "ludwig.idempotency.purge", name = "enabled", matchIfMissing = true)
    public IdempotencyPurgeJob idempotencyPurgeJob(TaskScheduler idempotencyTaskScheduler,
                                                   PostgresIdempotencyStore store, RunLock lock,
                                                   IdempotencyProperties properties,
                                                   IdempotencyMetrics metrics,
                                                   ObjectProvider<Clock> clock) {
        return new IdempotencyPurgeJob(idempotencyTaskScheduler, store, lock, properties, metrics,
                IdempotencyAutoConfiguration.clockOf(clock));
    }
}
