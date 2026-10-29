package ru.ludwigandreas.job.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.job.core.admin.ReplicaAdminOperations;
import ru.ludwigandreas.job.core.admin.service.ReplicaAdminService;
import ru.ludwigandreas.job.core.admin.web.ReplicaAdminController;
import ru.ludwigandreas.job.core.claim.JobInstanceIdentity;
import ru.ludwigandreas.job.core.control.ControlStore;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.job.core.control.ReplicaControlPoller;
import ru.ludwigandreas.job.core.control.ReplicaControlValidator;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminGrants;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;

/**
 * The replica-control channel and the {@code replicas} area, where job-core runs beside the administrative surface:
 * every replica heartbeats and applies the controls modules contribute, and any replica accepts a submission.
 */
@AutoConfiguration(after = JobCoreAutoConfiguration.class,
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, DataSource.class, RunLock.class})
@ConditionalOnProperty(prefix = "ludwig.job-core.control", name = "enabled", matchIfMissing = true)
public class ReplicaControlAutoConfiguration {

    @Bean
    public ReplicaAdminOperations ludwigReplicaAdminOperations() {
        return new ReplicaAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public ControlStore ludwigReplicaControlStore(DataSource dataSource) {
        return new ControlStore(dataSource);
    }

    /** The controls by name, validated against the catalog at startup. */
    @Bean
    public ReplicaControls ludwigReplicaControls(ObjectProvider<ReplicaControl<?>> controls,
                                                 AdminOperationCatalog catalog) {
        return new ReplicaControls(ReplicaControlValidator.index(controls.orderedStream().toList(), catalog));
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler ludwigReplicaControlScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("replica-control-");
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public ReplicaControlPoller ludwigReplicaControlPoller(ThreadPoolTaskScheduler ludwigReplicaControlScheduler,
                                                           ControlStore store, JobInstanceIdentity identity,
                                                           ReplicaControls controls, ObjectProvider<ObjectMapper> json,
                                                           JobCoreProperties properties, RunLock lock) {
        JobCoreProperties.Control control = properties.getControl();
        return new ReplicaControlPoller(ludwigReplicaControlScheduler, store, identity.owner(), controls.byName(),
                mapper(json), new ReplicaControlPoller.ControlSettings(control.getPollInterval(),
                        control.getHandlerTimeout(), control.getRetention()), lock);
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReplicaAdminService ludwigReplicaAdminService(ControlStore store, ReplicaControls controls,
                                                         JobInstanceIdentity identity, JobCoreProperties properties,
                                                         AdminGrants grants, AdminAccessEvaluator evaluator,
                                                         AdminAudit audit, ObjectProvider<ObjectMapper> json,
                                                         ObjectProvider<Validator> validator) {
        JobCoreProperties.Control control = properties.getControl();
        return new ReplicaAdminService(store, controls.byName(), identity.owner(), control.getPollInterval(),
                control.getAckTimeout(), grants, evaluator, audit, mapper(json), validator.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReplicaAdminController ludwigReplicaAdminController(ReplicaAdminService service) {
        return new ReplicaAdminController(service);
    }

    private static ObjectMapper mapper(ObjectProvider<ObjectMapper> json) {
        return json.getIfAvailable(ObjectMapper::new).copy().findAndRegisterModules();
    }

    /**
     * The validated controls, as one bean.
     *
     * @param byName the controls by name
     */
    public record ReplicaControls(Map<String, ReplicaControl<?>> byName) {
    }
}
