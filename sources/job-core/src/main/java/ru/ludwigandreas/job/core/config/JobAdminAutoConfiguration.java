package ru.ludwigandreas.job.core.config;

import java.time.Clock;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.job.core.admin.JobAdminOperations;
import ru.ludwigandreas.job.core.admin.service.JobAdminService;
import ru.ludwigandreas.job.core.admin.web.JobAdminController;
import ru.ludwigandreas.job.core.approval.AdminApprovalSweep;
import ru.ludwigandreas.job.core.approval.JdbcAdminApprovalStore;
import ru.ludwigandreas.job.core.approval.OnApprovalRequired;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockAdministration;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.security.admin.AdminApprovalStore;
import ru.ludwigandreas.security.admin.AdminApprovals;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code jobs} administrative area, registered only where the administrative surface exists.
 *
 * <p>Conditional on the security starter's {@link AdminOperationCatalog}, which exists only with the security
 * starter, web-core's problem pipeline and the surface switched on - so a service using job-core without them gets
 * no endpoint, never an unguarded one.
 */
@AutoConfiguration(afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, DataSource.class})
public class JobAdminAutoConfiguration {

    @Bean
    public JobAdminOperations ludwigJobAdminOperations() {
        return new JobAdminOperations();
    }

    @Bean
    public ProblemMessageBundle ludwigJobCoreMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-job-core-messages");
    }

    @Bean
    @ConditionalOnMissingBean
    public RunLockAdministration ludwigRunLockAdministration(DataSource dataSource) {
        return new RunLockAdministration(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobAdminService ludwigJobAdminService(ObjectProvider<SelfSchedulingLifecycle> jobs,
                                                 RunLockAdministration leases, AdminAudit audit,
                                                 ObjectProvider<Clock> clock) {
        Map<String, SelfSchedulingLifecycle> byName = jobs.orderedStream()
                .collect(Collectors.toMap(SelfSchedulingLifecycle::jobName, job -> job));
        return new JobAdminService(byName, leases, audit, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public JobAdminController ludwigJobAdminController(JobAdminService service) {
        return new JobAdminController(service);
    }

    /**
     * The administrative surface's four-eyes approvals, kept in job-core's schema. Declared whether or not any
     * operation requires approval: with none it is never written to.
     */
    @Bean
    @ConditionalOnMissingBean(AdminApprovalStore.class)
    public JdbcAdminApprovalStore ludwigAdminApprovalStore(DataSource dataSource) {
        return new JdbcAdminApprovalStore(dataSource);
    }

    /** Expiry and interruption of approvals, scheduled only where some operation requires approval. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(OnApprovalRequired.class)
    static class ApprovalSweepConfiguration {

        @Bean(destroyMethod = "shutdown")
        public ThreadPoolTaskScheduler ludwigAdminApprovalScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("admin-approval-");
            return scheduler;
        }

        @Bean
        @ConditionalOnMissingBean
        public AdminApprovalSweep ludwigAdminApprovalSweep(AdminApprovals approvals, RunLock lock,
                                                           ThreadPoolTaskScheduler ludwigAdminApprovalScheduler) {
            return new AdminApprovalSweep(ludwigAdminApprovalScheduler, approvals, lock);
        }
    }
}
