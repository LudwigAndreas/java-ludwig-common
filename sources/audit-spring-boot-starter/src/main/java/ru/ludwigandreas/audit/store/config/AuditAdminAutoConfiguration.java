package ru.ludwigandreas.audit.store.config;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.audit.store.admin.AuditAdminOperations;
import ru.ludwigandreas.audit.store.admin.service.AuditTrailSearch;
import ru.ludwigandreas.audit.store.admin.web.AuditTrailController;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;
import ru.ludwigandreas.db.core.query.AdminQueryFilter;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;

/**
 * The {@code audit} administrative area, registered only where the administrative surface and the stored trail both
 * exist.
 */
@AutoConfiguration(after = AuditPersistenceAutoConfiguration.class,
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, AuditEventRepository.class})
public class AuditAdminAutoConfiguration {

    @Bean
    public AuditAdminOperations ludwigAuditAdminOperations() {
        return new AuditAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditTrailSearch ludwigAuditTrailSearch(AuditEventRepository events, AdminQueryFilter filters,
                                                   AdminAudit audit, ObjectProvider<Clock> clock) {
        return new AuditTrailSearch(events, filters, audit, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditTrailController ludwigAuditTrailController(AuditTrailSearch search) {
        return new AuditTrailController(search);
    }
}
