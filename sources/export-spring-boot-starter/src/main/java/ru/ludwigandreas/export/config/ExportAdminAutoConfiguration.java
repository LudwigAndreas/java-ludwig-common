package ru.ludwigandreas.export.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.db.core.query.AdminQueryFilter;
import ru.ludwigandreas.export.admin.ExportAdminOperations;
import ru.ludwigandreas.export.admin.service.ExportAdminService;
import ru.ludwigandreas.export.admin.web.ExportAdminController;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.export.repository.ExportReportSubscriptionRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;

/**
 * The {@code exports} administrative area, registered only where the administrative surface exists.
 */
@AutoConfiguration(after = {ExportAutoConfiguration.class, ExportPersistenceAutoConfiguration.class},
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, ExportRunService.class})
public class ExportAdminAutoConfiguration {

    @Bean
    public ExportAdminOperations ludwigExportAdminOperations() {
        return new ExportAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExportAdminService ludwigExportAdminService(ExportReportRunRepository runs,
                                                       ExportReportSubscriptionRepository subscriptions,
                                                       ExportRunService lifecycle, ExportProperties properties,
                                                       AdminQueryFilter filters, AdminAudit audit) {
        return new ExportAdminService(runs, subscriptions, lifecycle, properties.getWeb().getBasePath(), filters,
                audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExportAdminController ludwigExportAdminController(ExportAdminService service) {
        return new ExportAdminController(service);
    }
}
