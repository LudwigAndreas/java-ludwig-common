package ru.ludwigandreas.identity.config;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.identity.admin.IdentityAdminOperations;
import ru.ludwigandreas.identity.admin.service.IdentityAdminService;
import ru.ludwigandreas.identity.admin.web.IdentityAdminController;
import ru.ludwigandreas.identity.repository.SecurityGrantRepository;
import ru.ludwigandreas.identity.repository.SecurityPartnerRepository;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code identity} administrative area, registered only where the administrative surface exists.
 */
@AutoConfiguration(after = IdentityProjectionAutoConfiguration.class,
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.security.admin.AdminOperationCatalog")
@ConditionalOnBean({AdminOperationCatalog.class, SecurityPartnerRepository.class})
public class IdentityAdminAutoConfiguration {

    @Bean
    public IdentityAdminOperations ludwigIdentityAdminOperations() {
        return new IdentityAdminOperations();
    }

    @Bean
    public ProblemMessageBundle ludwigIdentityMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-identity-messages");
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IdentityAdminService ludwigIdentityAdminService(SecurityPartnerRepository partners,
                                                           SecurityGrantRepository grants,
                                                           SecurityUserRepository users,
                                                           AuthorityResolver resolver,
                                                           @Qualifier("ludwigAuthorityCache")
                                                           LudwigCache<PrincipalRef, Authorities> authorityCache,
                                                           ObjectProvider<DataScopeRegistry> scopes,
                                                           AdminAudit audit, ObjectProvider<Clock> clock) {
        return new IdentityAdminService(new IdentityAdminService.Tables(partners, grants, users), resolver,
                authorityCache, scopes.getIfAvailable(), audit, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityAdminController ludwigIdentityAdminController(IdentityAdminService service) {
        return new IdentityAdminController(service);
    }
}
