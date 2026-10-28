package ru.ludwigandreas.outbox.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.outbox.admin.OutboxAdminOperations;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminService;
import ru.ludwigandreas.outbox.admin.web.OutboxAdminController;
import ru.ludwigandreas.outbox.audit.OutboxAuditLogger;
import ru.ludwigandreas.outbox.repository.OutboxAdminQueries;
import ru.ludwigandreas.outbox.repository.OutboxStatusHistoryRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code outbox} administrative area, registered only where the administrative surface exists - so a service
 * using the outbox without the security starter gets no endpoint, never an unguarded one.
 */
@AutoConfiguration(after = OutboxAutoConfiguration.class,
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, OutboxAuditLogger.class})
public class OutboxAdminAutoConfiguration {

    @Bean
    public OutboxAdminOperations ludwigOutboxAdminOperations() {
        return new OutboxAdminOperations();
    }

    @Bean
    public ProblemMessageBundle ludwigOutboxMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-outbox-messages");
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxAdminQueries ludwigOutboxAdminQueries() {
        return new OutboxAdminQueries();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxAdminService ludwigOutboxAdminService(OutboxAdminQueries queries,
                                                       OutboxStatusHistoryRepository history,
                                                       OutboxAuditLogger transitions, AdminAudit audit) {
        return new OutboxAdminService(queries, history, transitions, audit);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxAdminController ludwigOutboxAdminController(OutboxAdminService service) {
        return new OutboxAdminController(service);
    }
}
