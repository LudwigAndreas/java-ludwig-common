package ru.ludwigandreas.job.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.job.core.claim.ClaimOwner;
import ru.ludwigandreas.job.core.claim.JobInstanceIdentity;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.MicrometerRunLockListener;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockListener;

import javax.sql.DataSource;

/**
 * Wires the one piece of {@code job-core} that needs a Spring context: the database-backed
 * {@link RunLock}.
 *
 * <p>The backoff calculator, the schedule spec and the claim helper are deliberately not beans. They
 * are per-job values - one module has a retry policy per task - so a single application-scoped bean
 * of each would be the wrong shape, and constructing them where they are used keeps the dependency
 * direction obvious.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.job-core", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(JobCoreProperties.class)
public class JobCoreAutoConfiguration {

    /**
     * This instance's identity, exposed as a named bean so that every module writing an owner column
     * writes the same string - two modules deriving their own identities would make a pod look like
     * two different instances in the database, which is exactly the confusion the column exists to
     * remove.
     *
     * @param properties the module's configuration
     * @return the resolved instance identity
     */
    @Bean
    @ConditionalOnMissingBean(JobInstanceIdentity.class)
    public JobInstanceIdentity jobInstanceIdentity(JobCoreProperties properties) {
        return new JobInstanceIdentity(ClaimOwner.resolve(properties.getOwner()));
    }

    /**
     * The shared run lock. Only registered when the application actually has a data source; a service
     * that uses this module for its scheduling base alone should not fail to start over a lock table
     * it never touches.
     *
     * <p>The listener is injected through an {@link ObjectProvider} with a no-op fallback, so that
     * this bean has one shape whether or not the application has Micrometer - the alternative, two
     * conditional {@code RunLock} beans, would make "which lock am I getting?" a question about the
     * classpath.
     *
     * @param dataSource the application's data source
     * @param identity   this instance's identity
     * @param properties the module's configuration
     * @param listener   instrumentation, when something registered any
     * @return the database-backed run lock
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(RunLock.class)
    public RunLock jobRunLock(DataSource dataSource, JobInstanceIdentity identity,
                              JobCoreProperties properties, ObjectProvider<RunLockListener> listener) {
        return new JdbcRunLock(dataSource, identity.owner(), properties.getLock().getDefaultLease(),
                listener.getIfAvailable(() -> RunLockListener.NOOP));
    }

    /**
     * Lock instrumentation, registered only when the consumer already has Micrometer.
     *
     * <p>{@code micrometer-core} is an {@code optional} dependency of this module: a library must not
     * drag an observability stack into an application that did not ask for one. The nested class is
     * what keeps that promise - {@code @ConditionalOnClass} on a nested {@code @Configuration} is
     * evaluated without loading the enclosing class's method signatures, so a consumer with no
     * Micrometer on the classpath never resolves {@link MeterRegistry} at all.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    public static class RunLockMetricsConfiguration {

        /**
         * Binds lock outcomes to the application's registry.
         *
         * <p>{@link ObjectProvider} rather than a plain parameter because having Micrometer on the
         * classpath is not the same as having a registry bean - a library that assumed it does is a
         * library that fails to start an application which merely happens to have the jar.
         *
         * @param registries the application's meter registry, if it has one
         * @return the Micrometer binding, or the no-op listener
         */
        @Bean
        @ConditionalOnMissingBean(RunLockListener.class)
        public RunLockListener jobRunLockListener(ObjectProvider<MeterRegistry> registries) {
            MeterRegistry registry = registries.getIfAvailable();
            return registry == null ? RunLockListener.NOOP : new MicrometerRunLockListener(registry);
        }
    }
}
