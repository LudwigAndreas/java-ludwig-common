package ru.ludwigandreas.security.config;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.audit.AuthorizationDeniedAuditListener;
import ru.ludwigandreas.security.data.CompositeDataScopeProvider;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePermissionEvaluator;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.operation.OperationAuthority;

/**
 * Wires data-level authorization: the mapping registry, the scope providers, the guard the service
 * calls, and the {@link PermissionEvaluator} that makes {@code hasPermission(...)} work in
 * {@code @PreAuthorize}/{@code @PostAuthorize}.
 *
 * <p>Method security itself is enabled by {@link OperationAutoConfiguration}, whatever this configuration's switch
 * says: a service that forgets {@code @EnableMethodSecurity} gets code that looks protected and enforces nothing, and
 * turning row scoping off must not turn operation checks off with it.
 */
@AutoConfiguration(after = OperationAutoConfiguration.class)
// Both names must be true: turning the module off as a whole must also take the guard and the
// permission evaluator with it, or the context fails on beans whose collaborators are gone.
@ConditionalOnProperty(prefix = "ludwig.security", name = {"enabled", "data.enabled"},
        matchIfMissing = true)
public class DataAuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataScopeRegistry.class)
    public DataScopeRegistry ludwigDataScopeRegistry(ObjectProvider<DataScopeMapping<?>> mappings,
                                                      SecurityProperties properties) {
        List<DataScopeMapping<?>> registered = mappings.orderedStream().toList();
        return new DataScopeRegistry(registered, Set.copyOf(properties.getData().getUnscopedResources()));
    }

    @Bean
    @ConditionalOnMissingBean(DataScopePredicateFactory.class)
    public DataScopePredicateFactory ludwigDataScopePredicateFactory() {
        return new DataScopePredicateFactory();
    }

    /**
     * The per-subject grant providers a service or module contributes - the identity projection's grant rows, say -
     * united. The roles' scopes come from {@code ludwig.security.roles} through the operation model, which adds these
     * on top. Primary because it is the only one that speaks for all of them: injecting a bare
     * {@code DataScopeProvider} anywhere else would otherwise be ambiguous, and picking any single one silently drops
     * the grants the others carry.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(CompositeDataScopeProvider.class)
    public CompositeDataScopeProvider ludwigDataScopeProvider(ObjectProvider<DataScopeProvider> providers) {
        // Resolved on first use: with no contributed provider, resolving now would find only this bean itself.
        return new CompositeDataScopeProvider(() -> providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean(DataAccessGuard.class)
    public DataAccessGuard ludwigDataAccessGuard(OperationAuthority scopes,
                                                  DataScopeRegistry registry,
                                                  DataScopePredicateFactory predicateFactory,
                                                  AuditSink auditSink,
                                                  SecurityMetrics metrics,
                                                  SecurityProperties properties) {
        return new DataAccessGuard(scopes, registry, predicateFactory, auditSink, metrics,
                properties.getAudit().isEnabled() && properties.getAudit().isLogGrants());
    }

    @Bean
    @ConditionalOnMissingBean(PermissionEvaluator.class)
    public PermissionEvaluator ludwigPermissionEvaluator(DataAccessGuard guard, DataScopeRegistry registry,
                                                         OperationAuthority scopes) {
        return new DataScopePermissionEvaluator(guard, registry, scopes);
    }

    /**
     * Makes method security publish its decisions as application events. {@code @EnableMethodSecurity}
     * picks this bean up and hands it to the pre/post interceptors; without it a {@code @PreAuthorize}
     * denial is thrown and rendered, and observed by nothing.
     */
    @Bean
    @ConditionalOnMissingBean(AuthorizationEventPublisher.class)
    public AuthorizationEventPublisher ludwigAuthorizationEventPublisher(ApplicationEventPublisher publisher) {
        return new SpringAuthorizationEventPublisher(publisher);
    }

    @Bean
    @ConditionalOnMissingBean(AuthorizationDeniedAuditListener.class)
    @ConditionalOnProperty(prefix = "ludwig.security.audit", name = "enabled", matchIfMissing = true)
    public AuthorizationDeniedAuditListener ludwigAuthorizationDeniedAuditListener(
            AuditSink auditSink, SecurityMetrics metrics) {
        return new AuthorizationDeniedAuditListener(auditSink, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(MethodSecurityExpressionHandler.class)
    public MethodSecurityExpressionHandler ludwigMethodSecurityExpressionHandler(
            PermissionEvaluator permissionEvaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(permissionEvaluator);
        return handler;
    }
}
