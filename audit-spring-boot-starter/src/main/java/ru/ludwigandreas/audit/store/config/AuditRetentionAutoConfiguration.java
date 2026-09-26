package ru.ludwigandreas.audit.store.config;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.config.AuditProperties;
import ru.ludwigandreas.audit.store.metrics.AuditMetrics;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;
import ru.ludwigandreas.audit.store.retention.AuditRetentionPurge;
import ru.ludwigandreas.job.core.lock.RunLock;

/**
 * The retention purge, when {@code job-core} is on the classpath.
 *
 * <p>Behind {@code @ConditionalOnClass} on {@code job-core} rather than a hard dependency, because a
 * deployment that only wants the trail should not be made to put a scheduler and a lock table on its
 * classpath. The consequence is worth being loud about, and this module's README is: without {@code
 * job-core} nothing purges, and an audit table that grows forever is a disclosure risk with nobody
 * watching it.
 */
@AutoConfiguration
@ConditionalOnClass({RunLock.class, TaskScheduler.class})
@ConditionalOnBean({DataSource.class, RunLock.class})
@ConditionalOnProperty(prefix = "ludwig.audit.retention", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(AuditPersistenceAutoConfiguration.class)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditRetentionAutoConfiguration {

    /**
     * A scheduler of this module's own, so the purge runs whether or not the service enabled scheduling.
     *
     * <p>{@code SelfSchedulingLifecycle} exists for this reason: a starter that only works if the service
     * remembers {@code @EnableScheduling} is not plug-and-play, and the failure when it forgets is silent.
     * One thread, because one job is scheduled on it.
     *
     * @return the scheduler
     */
    @Bean(name = "auditTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "auditTaskScheduler")
    public TaskScheduler auditTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ludwig-audit-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * The purge.
     *
     * @param scheduler  this module's scheduler
     * @param repository the trail
     * @param properties the retention configuration
     * @param lock       the platform's distributed lock, so one replica purges rather than all of them
     * @param sink       where the purge's own {@code audit.purged} event goes
     * @param metrics    the purge counter, when a metrics stack is present
     * @param clock      supplies the cutoff; an {@code ObjectProvider} because this module neither adds nor
     *                   requires an unqualified {@code Clock} bean - see {@code AuditCoreAutoConfiguration}
     * @return the job
     */
    @Bean
    @ConditionalOnMissingBean(AuditRetentionPurge.class)
    public AuditRetentionPurge auditRetentionPurge(
            @org.springframework.beans.factory.annotation.Qualifier("auditTaskScheduler")
            TaskScheduler scheduler,
            AuditEventRepository repository, AuditProperties properties, RunLock lock,
            AuditSink sink, ObjectProvider<AuditMetrics> metrics, ObjectProvider<Clock> clock) {
        return new AuditRetentionPurge(scheduler, repository, properties, lock, sink,
                metrics.getIfAvailable(), clock.getIfAvailable(Clock::systemUTC));
    }
}
