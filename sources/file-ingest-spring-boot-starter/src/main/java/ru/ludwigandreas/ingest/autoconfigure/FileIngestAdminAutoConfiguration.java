package ru.ludwigandreas.ingest.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.ingest.admin.FileIngestAdminOperations;
import ru.ludwigandreas.ingest.admin.service.FileIngestAdminService;
import ru.ludwigandreas.ingest.admin.web.FileIngestAdminController;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.engine.IngestTaskRegistry;
import ru.ludwigandreas.ingest.repository.FileIngestQuarantineRepository;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;

/**
 * The {@code fileingest} administrative area, registered only where the administrative surface exists. It replaces
 * the {@code fileingest} actuator endpoint.
 */
@AutoConfiguration(after = FileIngestAutoConfiguration.class,
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, IngestTaskRegistry.class})
public class FileIngestAdminAutoConfiguration {

    @Bean
    public FileIngestAdminOperations ludwigFileIngestAdminOperations() {
        return new FileIngestAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileIngestAdminService ludwigFileIngestAdminService(IngestTaskRegistry registry,
                                                               FileIngestRunRepository runs,
                                                               FileIngestQuarantineRepository quarantines,
                                                               IngestPass pass, AdminAudit audit) {
        return new FileIngestAdminService(registry, runs, quarantines, pass, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public FileIngestAdminController ludwigFileIngestAdminController(FileIngestAdminService service) {
        return new FileIngestAdminController(service);
    }
}
