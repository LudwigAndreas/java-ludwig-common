package ru.ludwigandreas.messaging.db.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.messaging.db.admin.MessagingAdminOperations;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService;
import ru.ludwigandreas.messaging.db.admin.web.MessagingAdminController;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code messaging} administrative area, registered only where the administrative surface and the database log
 * both exist.
 */
@AutoConfiguration(after = MessagingDbAutoConfiguration.class,
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, LogStore.class})
public class MessagingDbAdminAutoConfiguration {

    @Bean
    public MessagingAdminOperations ludwigMessagingAdminOperations() {
        return new MessagingAdminOperations();
    }

    @Bean
    public ProblemMessageBundle ludwigMessagingDbMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-messaging-db-messages");
    }

    @Bean
    @ConditionalOnMissingBean
    public MessagingAdminService ludwigMessagingAdminService(LogStore store) {
        return new MessagingAdminService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessagingAdminController ludwigMessagingAdminController(MessagingAdminService service) {
        return new MessagingAdminController(service);
    }
}
