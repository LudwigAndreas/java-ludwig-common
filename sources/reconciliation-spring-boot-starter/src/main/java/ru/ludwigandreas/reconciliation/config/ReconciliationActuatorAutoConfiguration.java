package ru.ludwigandreas.reconciliation.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.db.core.audit.AuditorProvider;
import ru.ludwigandreas.reconciliation.actuator.ReconciliationEndpoint;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.engine.ReconciliationRuntime;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.util.Optional;

/**
 * Registers the {@code reconciliation} actuator endpoint, when Actuator is present and the endpoint
 * has been exposed.
 *
 * <p>{@code @ConditionalOnAvailableEndpoint} rather than a bare class check: an endpoint bean that
 * exists but is not exposed is a bean nobody can reach, and creating it anyway would mean this
 * module's ops surface appears in a heap dump of a service that deliberately turned it off.
 */
@AutoConfiguration
@ConditionalOnClass(Endpoint.class)
@AutoConfigureAfter(ReconciliationAutoConfiguration.class)
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationActuatorAutoConfiguration {

    /**
     * The endpoint.
     *
     * @param registry    the discovered tasks
     * @param runtime     the scheduled passes
     * @param runner      the fetch run, for dry runs and backfills
     * @param taskState   cursor and watermark
     * @param records     the staging table
     * @param jobs        the job table
     * @param leases      the lease table
     * @param quota       the quota
     * @param properties  the bound configuration
     * @param auditLogger the audit trail
     * @param auditors    who is calling, when the application publishes an auditor provider
     * @return the endpoint
     */
    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = ReconciliationEndpoint.class)
    @ConditionalOnMissingBean(ReconciliationEndpoint.class)
    @SuppressWarnings({"checkstyle:ParameterNumber", "unchecked"})
    public ReconciliationEndpoint reconciliationEndpoint(TaskRegistry registry,
                                                         ReconciliationRuntime runtime,
                                                         TaskRunner runner,
                                                         TaskStateService taskState,
                                                         SyncInboxRecordRepository records,
                                                         SyncRemoteJobRepository jobs,
                                                         QuotaLeaseRepository leases,
                                                         DatabaseQuota quota,
                                                         ReconciliationProperties properties,
                                                         AuditSink auditLogger,
                                                         ObjectProvider<AuditorProvider<?>> auditors) {
        AuditorProvider<String> auditor = auditors.stream()
                .findFirst()
                .map(provider -> (AuditorProvider<String>) provider)
                .orElse(Optional::empty);
        return new ReconciliationEndpoint(registry, runtime, runner, taskState, records, jobs, leases,
                quota, properties, auditLogger, auditor);
    }
}
