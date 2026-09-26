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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.audit.AuthorizationDeniedAuditListener;
import ru.ludwigandreas.security.data.CompositeDataScopeProvider;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePermissionEvaluator;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.data.DataScopePolicyValidator;
import ru.ludwigandreas.security.data.PolicyDataScopeProvider;
import ru.ludwigandreas.security.data.ScopePolicies;
import ru.ludwigandreas.security.metrics.SecurityMetrics;

/**
 * Wires data-level authorization: the mapping registry, the scope providers, the guard the service
 * calls, and the {@link PermissionEvaluator} that makes {@code hasPermission(...)} work in
 * {@code @PreAuthorize}/{@code @PostAuthorize}.
 *
 * <p>Enabling method security here rather than leaving it to the service is deliberate: the
 * annotations are the most-used half of the module, and a service that forgets
 * {@code @EnableMethodSecurity} gets code that looks protected, compiles, passes review and enforces
 * nothing. Failing to enable it is invisible; enabling it when the service did not expect it is not.
 */
@AutoConfiguration
@EnableMethodSecurity
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
     * The policy tree, parsed and normalized once. Creating it here rather than inside the provider
     * means a malformed grant expression fails context startup, and lets the validator below walk the
     * same compiled structure the provider will use at runtime.
     */
    @Bean
    @ConditionalOnMissingBean(ScopePolicies.class)
    public ScopePolicies ludwigScopePolicies(SecurityProperties properties) {
        return ScopePolicies.compile(properties);
    }

    /**
     * The configuration-driven provider is registered under its own concrete type, so a service adding
     * its own {@link DataScopeProvider} bean composes with it rather than replacing it - grants from
     * configuration and grants from a lookup are both real and both have to be honored.
     */
    @Bean
    @ConditionalOnMissingBean(PolicyDataScopeProvider.class)
    public PolicyDataScopeProvider ludwigPolicyDataScopeProvider(ScopePolicies policies,
                                                                  SecurityProperties properties) {
        return new PolicyDataScopeProvider(policies, properties);
    }

    /**
     * Fails startup when a policy names a resource or a dimension nothing can enforce. Without it the
     * same mistakes surface as exceptions from whichever request first exercises the combination.
     */
    @Bean
    @ConditionalOnMissingBean(DataScopePolicyValidator.class)
    public DataScopePolicyValidator ludwigDataScopePolicyValidator(ScopePolicies policies,
                                                                    DataScopeRegistry registry) {
        return new DataScopePolicyValidator(policies, registry);
    }

    /**
     * Primary because it is the only one that speaks for all of them: injecting a bare
     * {@code DataScopeProvider} anywhere else would otherwise be ambiguous between this, the
     * configuration-driven one, and every provider the service added - and picking any single one of
     * those silently drops the grants the others carry.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(CompositeDataScopeProvider.class)
    public CompositeDataScopeProvider ludwigDataScopeProvider(ObjectProvider<DataScopeProvider> providers) {
        return new CompositeDataScopeProvider(providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean(DataAccessGuard.class)
    public DataAccessGuard ludwigDataAccessGuard(CompositeDataScopeProvider scopeProvider,
                                                  DataScopeRegistry registry,
                                                  DataScopePredicateFactory predicateFactory,
                                                  AuditSink auditSink,
                                                  SecurityMetrics metrics,
                                                  SecurityProperties properties) {
        return new DataAccessGuard(scopeProvider, registry, predicateFactory, auditSink, metrics,
                properties.getAudit().isEnabled() && properties.getAudit().isLogGrants());
    }

    @Bean
    @ConditionalOnMissingBean(PermissionEvaluator.class)
    public PermissionEvaluator ludwigPermissionEvaluator(DataAccessGuard guard, DataScopeRegistry registry) {
        return new DataScopePermissionEvaluator(guard, registry);
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
