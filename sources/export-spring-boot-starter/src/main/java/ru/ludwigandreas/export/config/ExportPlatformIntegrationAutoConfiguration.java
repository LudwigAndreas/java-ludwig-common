package ru.ludwigandreas.export.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.export.engine.ReportFilterParser;
import ru.ludwigandreas.export.engine.ReportScopeResolver;
import ru.ludwigandreas.export.filter.ODataReportFilterParser;
import ru.ludwigandreas.export.security.ReportAuthorities;
import ru.ludwigandreas.export.security.SecurityReportAuthorities;
import ru.ludwigandreas.export.security.SecurityReportScopeResolver;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.DataScopeRegistry;

/**
 * Where this module joins the platform's security and filter dialect, when either is present.
 *
 * <h2>Why this is its own autoconfiguration, ordered twice</h2>
 *
 * <p>These three beans used to be {@code @Configuration} members of {@link ExportWebAutoConfiguration},
 * and none of the three was ever created in a service that had everything they need. The reason is the
 * one recorded on {@link ExportWebMvcAutoConfiguration}: a member class is registered before the
 * enclosing class's own beans, and - worse here - before the autoconfigurations the enclosing class is
 * ordered after. So {@code @ConditionalOnBean(AuthorityResolver.class)} was evaluated while the
 * security starter's beans did not yet exist, and every condition failed.
 *
 * <p>What that cost is worth writing down, because two of the three failures were silent:
 *
 * <ul>
 *   <li>{@link ReportAuthorities} fell back to {@code NONE}, so every requester resolved to no
 *       authorities: a role-restricted column was omitted from every file, including for the people
 *       entitled to it. Visible, at least, as a missing column.</li>
 *   <li>{@link ReportScopeResolver} fell back to {@code UNRESTRICTED}, so a scoped report was written
 *       <em>without its data-scope predicate</em>. That is the failure this module exists to prevent,
 *       and nothing about the resulting file says it happened.</li>
 *   <li>{@link ReportFilterParser} fell back to refusing every filter, so any request carrying
 *       {@code $filter} was rejected as unfilterable.</li>
 * </ul>
 *
 * <p>The ordering is therefore two-sided and both sides are load-bearing. {@code afterName} the
 * security and filter autoconfigurations, so their beans exist when the conditions here are evaluated;
 * {@code before} {@link ExportWebAutoConfiguration}, so that its deliberately strict fallbacks
 * ({@code ReportAuthorities.NONE}, an unrestricted scope, a parser that refuses) back off in favour of
 * these. Names rather than classes for the first, because both starters are optional dependencies and
 * a class literal would have to be loadable.
 */
@AutoConfiguration(before = ExportWebAutoConfiguration.class,
        afterName = {
                "ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration",
                "ru.ludwigandreas.security.config.DataAuthorizationAutoConfiguration",
                "ru.ludwigandreas.identity.config.IdentityProjectionAutoConfiguration",
                "ru.ludwigandreas.odatafilter.config.ODataFilterAutoConfiguration"
        })
@ConditionalOnProperty(prefix = "ludwig.export", name = "enabled", matchIfMissing = true)
public class ExportPlatformIntegrationAutoConfiguration {

    /**
     * Re-resolves a requester's authorities, and scopes a report's rows with them.
     *
     * <p>Both in one member class, because they are two halves of one decision: re-resolving a
     * requester's authorities is only useful if something scopes the rows with them, and scoping the
     * rows is only correct if the authorities are current. A member class is the right tool here -
     * unlike in the case above - because the condition on it is {@code @ConditionalOnClass}, which is
     * answered from the classpath and needs no bean to exist yet.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AuthorityResolver.class)
    public static class SecurityIntegration {

        /**
         * The requester's authorities, as they stand now rather than as the token said.
         *
         * @param resolver the platform's resolver
         * @return the authority source
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(AuthorityResolver.class)
        public ReportAuthorities reportAuthorities(AuthorityResolver resolver) {
            return new SecurityReportAuthorities(resolver);
        }

        /**
         * The row-level scope predicate the base query always carries.
         *
         * @param scopes     resolves a principal's scope for a resource
         * @param mappings   the registered resource mappings
         * @param predicates turns a scope into a QueryDSL predicate
         * @return the scope resolver
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean({DataScopeProvider.class, DataScopeRegistry.class})
        public ReportScopeResolver reportScopeResolver(DataScopeProvider scopes,
                                                      DataScopeRegistry mappings,
                                                      DataScopePredicateFactory predicates) {
            return new SecurityReportScopeResolver(scopes, mappings, predicates);
        }
    }

    /** Parses {@code $filter} with the platform's one filter dialect, when it is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ODataFilterService.class)
    public static class ODataIntegration {

        /**
         * The filter parser.
         *
         * @param filters the platform's OData filter service
         * @return the parser
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(ODataFilterService.class)
        public ReportFilterParser reportFilterParser(ODataFilterService filters) {
            return new ODataReportFilterParser(filters);
        }
    }
}
