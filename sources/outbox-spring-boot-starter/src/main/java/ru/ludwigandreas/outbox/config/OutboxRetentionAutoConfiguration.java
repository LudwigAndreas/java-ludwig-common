package ru.ludwigandreas.outbox.config;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.config.JobCoreAutoConfiguration;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.scheduler.OutboxRetentionPurge;

/**
 * Registers the retention purge, on by default.
 *
 * <p>Its own auto-configuration, ordered after {@code job-core}'s, because the purge needs the platform's
 * {@link RunLock} and a {@code @ConditionalOnBean} is only reliable once the configuration declaring that bean has
 * been processed. Without a lock the purge is not registered at all - running it unlocked on every replica would only
 * multiply the deletes' lock contention - and a startup warning says retention is inactive, because a table that
 * silently stops being purged is found only when the disk fills.
 *
 * <p>The settings are validated here, when the job is created, so a disabled retention is never refused for values
 * nobody uses.
 */
@AutoConfiguration(after = {OutboxAutoConfiguration.class, OutboxMetricsAutoConfiguration.class,
        JobCoreAutoConfiguration.class})
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "ludwig.outbox", name = "enabled", matchIfMissing = true)
@ConditionalOnBean(OutboxMessageRepository.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxRetentionAutoConfiguration {

    /**
     * The purge job.
     *
     * @param repository    the outbox table
     * @param properties    the outbox settings
     * @param lock          the platform's distributed lock
     * @param auditSink     the platform's audit sink; named so, as everywhere in this module, because a context
     *                      with several sinks and none primary resolves the injection by this name
     * @param metrics       the outbox metrics
     * @param clocks        the application's clock, when it declares one
     * @param taskScheduler the module's scheduler
     * @return the job
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method, each argument its own bean.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean(OutboxRetentionPurge.class)
    @ConditionalOnBean(RunLock.class)
    @ConditionalOnProperty(prefix = "ludwig.outbox.retention", name = "enabled", matchIfMissing = true)
    public OutboxRetentionPurge outboxRetentionPurge(OutboxMessageRepository repository,
                                                     OutboxProperties properties,
                                                     RunLock lock,
                                                     AuditSink auditSink,
                                                     OutboxMetrics metrics,
                                                     ObjectProvider<Clock> clocks,
                                                     @Qualifier("outboxTaskScheduler") TaskScheduler taskScheduler) {
        OutboxProperties.Retention retention = properties.getRetention();
        retention.validate();
        return new OutboxRetentionPurge(taskScheduler, repository, retention, lock, auditSink, metrics,
                clocks.getIfAvailable(Clock::systemUTC));
    }

    /**
     * The warning for a deployment with retention on and no lock to run it under.
     *
     * @return a callback that logs once the context is up
     */
    @Bean
    @ConditionalOnMissingBean(RunLock.class)
    @ConditionalOnProperty(prefix = "ludwig.outbox.retention", name = "enabled", matchIfMissing = true)
    public SmartInitializingSingleton outboxRetentionInactiveWarning() {
        return () -> LoggerFactory.getLogger(OutboxRetentionAutoConfiguration.class).warn(
                "Outbox retention is enabled but no RunLock bean exists (job-core's lock needs a DataSource), so"
                        + " PUBLISHED, DEAD_LETTER and DISCARDED messages are not purged. Provide a RunLock, or set"
                        + " ludwig.outbox.retention.enabled=false to make that deliberate.");
    }
}
