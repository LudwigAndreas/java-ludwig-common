package ru.ludwigandreas.messaging.db.config;

import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import ru.ludwigandreas.messaging.db.admin.MessagingAdminOperations;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService;
import ru.ludwigandreas.messaging.db.admin.web.MessagingAdminController;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.security.admin.AdminAudit;
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
    public MessagingAdminService ludwigMessagingAdminService(LogStore store,
                                                             ObjectProvider<KafkaListenerEndpointRegistry> listeners,
                                                             AdminAudit audit) {
        // The groups this service's own listeners read as; another service's groups on a shared database are not this
        // service's to pause or move.
        return new MessagingAdminService(store, () -> listeners.stream()
                .flatMap(registry -> registry.getAllListenerContainers().stream())
                .map(MessageListenerContainer::getGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()), audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessagingAdminController ludwigMessagingAdminController(MessagingAdminService service) {
        return new MessagingAdminController(service);
    }
}
