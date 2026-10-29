package ru.ludwigandreas.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminApprovalPolicy;
import ru.ludwigandreas.security.admin.AdminCallerPolicy;
import ru.ludwigandreas.security.admin.AdminApprovals;
import ru.ludwigandreas.security.admin.AdminApprovalsController;
import ru.ludwigandreas.security.admin.AdminApprovalStore;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminGrants;
import ru.ludwigandreas.security.admin.AdminHandlerValidator;
import ru.ludwigandreas.security.admin.AdminObjectScope;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminOperationContributor;
import ru.ludwigandreas.security.admin.AdminOperationsController;
import ru.ludwigandreas.security.admin.AdminProperties;
import ru.ludwigandreas.security.admin.AdminSurfaceGuard;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.ScopePolicies;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * The administrative surface: the operation catalog, the service's grants, the guard on {@code /admin/**}, and the
 * discovery endpoint.
 *
 * <p>Present only where it can be secured and rendered - a servlet application with this module's authorization
 * stack and web-core's problem pipeline. A module that declares administrative endpoints registers them only when
 * the {@link AdminOperationCatalog} bean exists, so without this configuration it serves none: the surface fails
 * closed, never open.
 *
 * <p>Independent of method security. The guard evaluates {@code @AdminAccess} itself, so turning off
 * {@code ludwig.security.data.enabled}, which takes {@code @EnableMethodSecurity} with it, does not turn off the
 * administrative authorization.
 */
@AutoConfiguration(after = {LudwigSecurityAutoConfiguration.class, DataAuthorizationAutoConfiguration.class},
        afterName = "ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ProblemMessages.class, WebMvcConfigurer.class})
@ConditionalOnBean({AuthorityLookup.class, ProblemMessages.class})
@ConditionalOnProperty(prefix = "ludwig.admin", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminAutoConfiguration {

    /** Any path under the surface; a public-path pattern matching it would open the surface. */
    private static final String PROBE = "/admin/v1/probe";

    @Bean
    @ConditionalOnMissingBean
    public AdminOperationCatalog ludwigAdminOperationCatalog(ObjectProvider<AdminOperationContributor> contributors) {
        return new AdminOperationCatalog(contributors.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminApprovalPolicy ludwigAdminApprovalPolicy(AdminProperties properties, AdminOperationCatalog catalog,
                                                         ObjectProvider<AdminApprovalStore> store) {
        // The store is read here, not injected: job-core declares it, and the surface must start without it while
        // no operation requires approval.
        return new AdminApprovalPolicy(properties.getApproval().getRequired(), properties.getApproval().getTtl(),
                catalog, store.getIfAvailable() != null);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminGrants ludwigAdminGrants(AdminProperties properties, AdminOperationCatalog catalog,
                                         SecurityProperties securityProperties) {
        refusePublicAdminPaths(securityProperties.getPublicPaths());
        return new AdminGrants(properties.getGrants(), catalog);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminAccessEvaluator ludwigAdminAccessEvaluator(AdminGrants grants,
                                                           ObjectProvider<AuthorizationEventPublisher> publisher,
                                                           ApplicationEventPublisher events,
                                                           AdminObjectScope objects) {
        // Method security's publisher when there is one, so denials share its audit path; a plain one otherwise,
        // because ludwig.security.data.enabled=false removes that bean and the denials must still be recorded.
        return new AdminAccessEvaluator(grants,
                publisher.getIfAvailable(() -> new SpringAuthorizationEventPublisher(events)), objects);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminObjectScope ludwigAdminObjectScope(ObjectProvider<ScopePolicies> policies,
                                                   ObjectProvider<DataScopeProvider> provider) {
        // Both absent when ludwig.security.data.enabled=false: then nothing is scoped by object.
        return new AdminObjectScope(policies.getIfAvailable(), provider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminCallerPolicy ludwigAdminCallerPolicy(AdminAccessEvaluator evaluator, AdminProperties properties) {
        return new AdminCallerPolicy(evaluator, properties.isAllowPersonalAccessTokens(),
                Set.copyOf(properties.getHumanCallers().getRequiredVia()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminSurfaceGuard ludwigAdminSurfaceGuard(AdminOperationCatalog catalog, AdminAccessEvaluator evaluator,
                                                     AdminCallerPolicy callers, AdminApprovals approvals) {
        return new AdminSurfaceGuard(catalog, evaluator, callers, approvals);
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AdminApprovals ludwigAdminApprovals(AdminApprovalPolicy policy, ObjectProvider<AdminApprovalStore> store,
                                               AdminGrants grants, AuthorityLookup authorities, AdminAudit audit,
                                               WebApplicationContext context, ObjectProvider<ObjectMapper> json,
                                               ObjectProvider<Clock> clock) {
        return AdminApprovals.create(policy, store.getIfAvailable(), grants, authorities, audit, context,
                json.getIfAvailable(ObjectMapper::new).copy().findAndRegisterModules(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminApprovalsController ludwigAdminApprovalsController(AdminApprovals approvals) {
        return new AdminApprovalsController(approvals);
    }

    @Bean
    public WebMvcConfigurer ludwigAdminSurfaceGuardRegistration(AdminSurfaceGuard guard) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(guard).addPathPatterns(AdminSurfaceGuard.PATH_PATTERN);
            }
        };
    }

    @Bean
    public AdminHandlerValidator ludwigAdminHandlerValidator(ListableBeanFactory beanFactory,
                                                             AdminOperationCatalog catalog,
                                                             ProblemMessages messages) {
        return new AdminHandlerValidator(beanFactory, catalog, messages);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminAudit ludwigAdminAudit(AuditSink sink) {
        return new AdminAudit(sink);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminOperationsController ludwigAdminOperationsController(AdminGrants grants, ProblemMessages messages) {
        return new AdminOperationsController(grants, messages);
    }

    /**
     * A public path that matches the surface would make every administrative endpoint anonymous under the
     * platform's chain. The guard would still refuse the request, but a configuration that says the opposite of
     * what happens is refused at startup rather than left to mislead whoever reads it.
     */
    static void refusePublicAdminPaths(List<String> publicPaths) {
        AntPathMatcher matcher = new AntPathMatcher();
        for (String path : publicPaths) {
            String trimmed = path.trim();
            if (trimmed.startsWith("/admin/") || matcher.match(trimmed, PROBE)) {
                throw new SecurityConfigurationException("ludwig.security.public-paths contains '" + path
                        + "', which matches the administrative surface under /admin/v1/. Administrative endpoints"
                        + " are never public; remove or narrow the entry.");
            }
        }
    }
}
