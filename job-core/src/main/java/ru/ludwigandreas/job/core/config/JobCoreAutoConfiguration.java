package ru.ludwigandreas.job.core.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.job.core.claim.ClaimOwner;
import ru.ludwigandreas.job.core.claim.JobInstanceIdentity;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLock;

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
     * @param dataSource the application's data source
     * @param identity   this instance's identity
     * @return the database-backed run lock
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(RunLock.class)
    public RunLock jobRunLock(DataSource dataSource, JobInstanceIdentity identity) {
        return new JdbcRunLock(dataSource, identity.owner());
    }
}
