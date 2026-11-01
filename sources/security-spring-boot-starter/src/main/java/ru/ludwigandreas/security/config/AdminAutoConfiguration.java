package ru.ludwigandreas.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.admin.AdminApprovalPolicy;
import ru.ludwigandreas.security.admin.AdminCallerPolicy;
import ru.ludwigandreas.security.admin.AdminApprovals;
import ru.ludwigandreas.security.admin.AdminApprovalsController;
import ru.ludwigandreas.security.admin.AdminApprovalStore;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminProperties;
import ru.ludwigandreas.security.admin.AdminSurfaceGuard;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.security.admin.OperationHoldersController;
import ru.ludwigandreas.security.admin.SecurityAdminOperations;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationEvaluator;

/**
 * The administrative surface: the guard on {@code /admin/**}, who may reach it, four-eyes approval, the audit of its
 * changes, and who holds which operation.
 *
 * <p>Present only where it can be secured and rendered - a servlet application with this module's authorization
 * stack and web-core's problem pipeline. A module that declares administrative endpoints registers them only when
 * the {@link AdminSurfaceGuard} bean exists, so without this configuration it serves none: the surface fails closed,
 * never open.
 *
 * <p>The operations themselves, and the roles holding them, are the operation model's
 * ({@link OperationAutoConfiguration}), shared with every other surface.
 */
@AutoConfiguration(after = {LudwigSecurityAutoConfiguration.class, OperationAutoConfiguration.class,
    DataAuthorizationAutoConfiguration.class},
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
    public AdminApprovalPolicy ludwigAdminApprovalPolicy(AdminProperties properties, OperationCatalog catalog,
                                                         ObjectProvider<AdminApprovalStore> store) {
        // The store is read here, not injected: job-core declares it, and the surface must start without it while
        // no operation requires approval.
        return new AdminApprovalPolicy(properties.getApproval().getRequired(), properties.getApproval().getTtl(),
                catalog, store.getIfAvailable() != null);
    }

    @Bean
    public SecurityAdminOperations ludwigSecurityAdminOperations(SecurityProperties securityProperties) {
        refusePublicAdminPaths(securityProperties.getPublicPaths());
        return new SecurityAdminOperations();
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminCallerPolicy ludwigAdminCallerPolicy(OperationEvaluator evaluator, AdminProperties properties,
                                                     SecurityProperties securityProperties) {
        return new AdminCallerPolicy(evaluator, securityProperties.getOperations().isAllowPersonalAccessTokens(),
                Set.copyOf(properties.getHumanCallers().getRequiredVia()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminSurfaceGuard ludwigAdminSurfaceGuard(OperationCatalog catalog, OperationEvaluator evaluator,
                                                     AdminCallerPolicy callers, AdminApprovals approvals) {
        return new AdminSurfaceGuard(catalog, evaluator, callers, approvals);
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public AdminApprovals ludwigAdminApprovals(AdminApprovalPolicy policy, ObjectProvider<AdminApprovalStore> store,
                                               OperationAuthority grants, AuthorityLookup authorities, AdminAudit audit,
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
    @ConditionalOnMissingBean
    public AdminAudit ludwigAdminAudit(AuditSink sink) {
        return new AdminAudit(sink);
    }

    @Bean
    @ConditionalOnMissingBean
    public OperationHoldersController ludwigOperationHoldersController(OperationAuthority authority) {
        return new OperationHoldersController(authority);
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
