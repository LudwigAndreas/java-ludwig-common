package ru.ludwigandreas.reconciliation.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.db.core.query.AdminQueryFilter;
import ru.ludwigandreas.reconciliation.admin.ReconciliationAdminOperations;
import ru.ludwigandreas.reconciliation.admin.service.ReconciliationAdminService;
import ru.ludwigandreas.reconciliation.admin.web.ReconciliationAdminController;
import ru.ludwigandreas.reconciliation.engine.ReconciliationRuntime;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationOperations;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRunRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminObjectScope;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code reconciliation} administrative area, registered only where the administrative surface exists. It
 * replaces the reconciliation actuator endpoint.
 */
@AutoConfiguration(after = {ReconciliationAutoConfiguration.class, ReconciliationEnumerationAutoConfiguration.class},
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, TaskRegistry.class})
public class ReconciliationAdminAutoConfiguration {

    @Bean
    public ReconciliationAdminOperations ludwigReconciliationAdminOperations() {
        return new ReconciliationAdminOperations();
    }

    @Bean
    public ProblemMessageBundle ludwigReconciliationMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-reconciliation-messages");
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReconciliationAdminService ludwigReconciliationAdminService(TaskRegistry registry,
                                                                     ReconciliationRuntime runtime,
                                                                     TaskRunner runner,
                                                                     TaskStateService taskState,
                                                                     SyncInboxRecordRepository records,
                                                                     SyncRemoteJobRepository jobs,
                                                                     QuotaLeaseRepository leases,
                                                                     DatabaseQuota quota,
                                                                     ReconciliationProperties properties,
                                                                     ObjectProvider<EnumerationOperations> enumerations,
                                                                     AdminAudit audit,
                                                                     SyncRunRepository runs,
                                                                     ObjectProvider<AdminObjectScope> objects,
                                                                     AdminQueryFilter filters) {
        return new ReconciliationAdminService(new ReconciliationAdminService.Engine(registry, runtime, runner,
                taskState, records, jobs, leases, quota, properties, runs), enumerations.getIfAvailable(), audit,
                objects.getIfAvailable(), filters);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReconciliationAdminController ludwigReconciliationAdminController(ReconciliationAdminService service) {
        return new ReconciliationAdminController(service);
    }
}
