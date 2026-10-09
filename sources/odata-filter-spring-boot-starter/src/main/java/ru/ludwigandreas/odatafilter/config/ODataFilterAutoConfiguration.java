package ru.ludwigandreas.odatafilter.config;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.GrantedAuthority;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.metrics.ODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.querydsl.PredicateBuilder;
import ru.ludwigandreas.odatafilter.security.AnonymousFilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.security.FilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.security.SpringSecurityFilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.validation.FilterValidator;

/**
 * Registers the framework-agnostic core: policy resolution, predicate translation, role
 * resolution and the {@link ODataFilterService} facade. Adding this starter as a dependency is
 * enough to get all of this wired up with sensible defaults; every bean here can be overridden by
 * simply declaring your own bean of the same type ({@code @ConditionalOnMissingBean}).
 *
 * <p>The {@code AuditSink} is resolved through an {@code ObjectProvider} and falls back to
 * {@code audit-core}'s {@code NoopAuditSink}. Not because the trail is optional - it is not - but
 * because {@code audit-core} is a plain library and a consumer that has not added
 * {@code audit-spring-boot-starter} has no sink bean to find. Falling back keeps a filter working in
 * that application; what it must never do is make {@code ODataFilterService} defend against the
 * absence by catching, which is {@code AuditFailurePolicy}'s decision and not this module's.
 */
@AutoConfiguration
@EnableConfigurationProperties(ODataFilterProperties.class)
public class ODataFilterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FilterPolicyRegistry filterPolicyRegistry(ODataFilterProperties properties) {
        return new FilterPolicyRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public PredicateBuilder odataPredicateBuilder() {
        return new PredicateBuilder();
    }

    @Bean
    @ConditionalOnMissingBean(FilterPrincipalResolver.class)
    @ConditionalOnClass(GrantedAuthority.class)
    public FilterPrincipalResolver springSecurityFilterPrincipalResolver() {
        return new SpringSecurityFilterPrincipalResolver();
    }

    @Bean
    @ConditionalOnMissingBean(FilterPrincipalResolver.class)
    public FilterPrincipalResolver anonymousFilterPrincipalResolver() {
        return new AnonymousFilterPrincipalResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ODataFilterService odataFilterService(
            ODataFilterProperties properties,
            FilterPolicyRegistry policyRegistry,
            PredicateBuilder predicateBuilder,
            FilterPrincipalResolver principalResolver,
            ObjectProvider<FilterValidator> validators,
            ApplicationEventPublisher eventPublisher,
            ODataFilterMetrics metrics,
            ObjectProvider<AuditSink> auditSink) {
        List<FilterValidator> orderedValidators = validators.orderedStream().toList();
        return new ODataFilterService(properties, policyRegistry, predicateBuilder, principalResolver,
                orderedValidators, eventPublisher, metrics, auditSink.getIfAvailable(NoopAuditSink::new));
    }
}
