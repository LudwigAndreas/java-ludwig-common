package ru.ludwigandreas.pat.autoconfigure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.pat.admin.PatAdminOperations;
import ru.ludwigandreas.pat.admin.service.PatAdminService;
import ru.ludwigandreas.pat.admin.web.PatAdminController;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.web.PatCredentialGuard;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;

/**
 * The {@code pat} administrative area, registered only where the administrative surface exists.
 */
@AutoConfiguration(after = {PatAutoConfiguration.class, PatWebAutoConfiguration.class},
        afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnBean({AdminOperationCatalog.class, PatService.class, PatCredentialGuard.class})
public class PatAdminAutoConfiguration {

    @Bean
    public PatAdminOperations ludwigPatAdminOperations() {
        return new PatAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public PatAdminService ludwigPatAdminService(PatService service, PatQueryRepository queries, AdminAudit audit,
                                                 Clock clock) {
        return new PatAdminService(service, queries, audit, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public PatAdminController ludwigPatAdminController(PatAdminService service) {
        return new PatAdminController(service);
    }

    /**
     * Refuses a personal access token on this area even where the surface admits them: the module's own rule that a
     * token may not mint, rotate or revoke a token does not depend on a surface-wide flag.
     *
     * @param guard the module's credential guard
     * @return the registration
     */
    @Bean
    public WebMvcConfigurer ludwigPatAdminGuardRegistration(PatCredentialGuard guard) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(guard).addPathPatterns("/admin/v1/pat/**");
            }
        };
    }
}
