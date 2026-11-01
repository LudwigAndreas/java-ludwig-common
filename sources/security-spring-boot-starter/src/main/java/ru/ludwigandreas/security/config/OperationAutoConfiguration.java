package ru.ludwigandreas.security.config;

import java.util.Locale;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import ru.ludwigandreas.security.data.CompositeDataScopeProvider;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.OperationDeclarationValidator;
import ru.ludwigandreas.security.operation.OperationDescriptions;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.security.operation.OperationGrants;
import ru.ludwigandreas.security.operation.OperationScopeValidator;
import ru.ludwigandreas.security.operation.OperationsController;
import ru.ludwigandreas.security.operation.RemovedOperationKeys;
import ru.ludwigandreas.security.operation.RequiresOperation;
import ru.ludwigandreas.security.operation.RequiresOperationAuthorizationManager;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * The operation model: the catalog every module contributes to, {@code ludwig.security.roles} compiled against it,
 * the authority that answers who holds what with which scope, the evaluation every surface shares, and
 * {@link RequiresOperation} enforced on bean methods through method security.
 *
 * <p>On whenever the security starter is, and before row-level authorization and the administrative surface, both of
 * which read it. Method security is enabled here rather than with row-level authorization, so that turning row
 * scoping off ({@code ludwig.security.data.enabled=false}) never turns an operation check off with it.
 */
@AutoConfiguration(after = LudwigSecurityAutoConfiguration.class,
        before = {DataAuthorizationAutoConfiguration.class, AdminAutoConfiguration.class},
        afterName = "ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration")
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
@ConditionalOnProperty(prefix = "ludwig.security", name = "enabled", matchIfMissing = true)
public class OperationAutoConfiguration {

    @Bean
    public RemovedOperationKeys ludwigRemovedOperationKeys(ConfigurableEnvironment environment) {
        return new RemovedOperationKeys(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public OperationCatalog ludwigOperationCatalog(ObjectProvider<OperationContributor> contributors) {
        return new OperationCatalog(contributors.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public OperationGrants ludwigOperationGrants(SecurityProperties properties, OperationCatalog catalog,
                                                 RemovedOperationKeys removed) {
        return OperationGrants.compile(properties.getRoles(), catalog);
    }

    @Bean
    @ConditionalOnMissingBean
    public OperationAuthority ludwigOperationAuthority(OperationGrants grants,
                                                       ObjectProvider<CompositeDataScopeProvider> contributed) {
        // Resolved per call: the contributed providers belong to row-level authorization, which runs after this and is
        // absent when row scoping is off.
        return new OperationAuthority(grants, contributed::getIfAvailable);
    }

    @Bean
    public OperationScopeValidator ludwigOperationScopeValidator(OperationGrants grants,
                                                                 ObjectProvider<DataScopeRegistry> registry) {
        return new OperationScopeValidator(grants, registry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public OperationEvaluator ludwigOperationEvaluator(OperationAuthority authority,
                                                       ObjectProvider<AuthorizationEventPublisher> publisher,
                                                       ApplicationEventPublisher events,
                                                       SecurityProperties properties) {
        // Method security's publisher when there is one, so denials share its audit path; a plain one otherwise.
        return new OperationEvaluator(authority,
                publisher.getIfAvailable(() -> new SpringAuthorizationEventPublisher(events)),
                properties.getOperations().isAllowPersonalAccessTokens());
    }

    @Bean
    public OperationDeclarationValidator ludwigOperationDeclarationValidator(
            ListableBeanFactory beanFactory, OperationCatalog catalog,
            ObjectProvider<OperationDescriptions> descriptions) {
        return new OperationDeclarationValidator(beanFactory, catalog, descriptions.getIfAvailable());
    }

    /**
     * {@link RequiresOperation} on any bean method, ordered just before {@code @PreAuthorize} so that the operation is
     * decided first.
     *
     * @param evaluator the evaluation, resolved on first use so that the advisor does not initialise it early
     * @return the advisor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor ludwigRequiresOperationAdvisor(ObjectProvider<OperationEvaluator> evaluator) {
        AuthorizationManagerBeforeMethodInterceptor interceptor = new AuthorizationManagerBeforeMethodInterceptor(
                new AnnotationMatchingPointcut(null, RequiresOperation.class, true),
                new RequiresOperationAuthorizationManager(evaluator));
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() - 1);
        return interceptor;
    }

    /** Descriptions, resolved from web-core's bundles where they are. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ru.ludwigandreas.webcore.problem.ProblemMessages")
    @ConditionalOnBean(type = "ru.ludwigandreas.webcore.problem.ProblemMessages")
    static class Descriptions {

        @Bean
        public OperationDescriptions ludwigOperationDescriptions(ProblemMessages messages) {
            // Locale.ROOT is the default bundle: ProblemMessages turns off the fall-back to the server's locale.
            return key -> messages.resolve(key, null, Locale.ROOT).isPresent();
        }
    }

    /** Discovery, where there is a servlet stack to serve it and web-core to localize it. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "ru.ludwigandreas.webcore.problem.ProblemMessages")
    @ConditionalOnBean(type = "ru.ludwigandreas.webcore.problem.ProblemMessages")
    static class Discovery {

        @Bean
        @ConditionalOnMissingBean
        public OperationsController ludwigOperationsController(OperationAuthority authority,
                                                               ProblemMessages messages) {
            return new OperationsController(authority, messages);
        }
    }
}
