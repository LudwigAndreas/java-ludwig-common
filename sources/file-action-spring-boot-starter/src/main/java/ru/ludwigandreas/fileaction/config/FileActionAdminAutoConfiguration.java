package ru.ludwigandreas.fileaction.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.fileaction.admin.FileActionAdminOperations;
import ru.ludwigandreas.fileaction.admin.service.FileActionAdminService;
import ru.ludwigandreas.fileaction.admin.web.FileActionAdminController;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;

/**
 * The {@code fileactions} administrative area, registered only where the administrative surface exists.
 */
@AutoConfiguration(after = {FileActionAutoConfiguration.class, FileActionPersistenceAutoConfiguration.class},
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, FileActionService.class})
public class FileActionAdminAutoConfiguration {

    @Bean
    public FileActionAdminOperations ludwigFileActionAdminOperations() {
        return new FileActionAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileActionAdminService ludwigFileActionAdminService(FileActionSubmissionRepository submissions,
                                                               FileActionService actions, AdminAudit audit) {
        return new FileActionAdminService(submissions, actions, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public FileActionAdminController ludwigFileActionAdminController(FileActionAdminService service) {
        return new FileActionAdminController(service);
    }
}
