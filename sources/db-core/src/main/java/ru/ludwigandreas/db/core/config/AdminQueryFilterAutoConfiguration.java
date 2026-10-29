package ru.ludwigandreas.db.core.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.db.core.query.AdminQueryFilter;
import ru.ludwigandreas.db.core.query.ODataAdminQueryFilter;
import ru.ludwigandreas.db.core.query.UnavailableAdminQueryFilter;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;

/**
 * The {@link AdminQueryFilter} administrative listings parse {@code $filter} with: the OData starter's where it runs,
 * a refusing one otherwise.
 */
@AutoConfiguration(afterName = "ru.ludwigandreas.odatafilter.config.ODataFilterAutoConfiguration")
@ConditionalOnClass(name = "ru.ludwigandreas.webcore.problem.LocalizedException")
public class AdminQueryFilterAutoConfiguration {

    /** Only where the OData starter is on the classpath; this class is never loaded otherwise. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ru.ludwigandreas.odatafilter.core.ODataFilterService")
    static class ODataBacked {

        @Bean
        @ConditionalOnBean(ODataFilterService.class)
        @ConditionalOnMissingBean(AdminQueryFilter.class)
        AdminQueryFilter ludwigAdminQueryFilter(ODataFilterService service) {
            return new ODataAdminQueryFilter(service);
        }
    }

    @Bean
    @ConditionalOnMissingBean(AdminQueryFilter.class)
    public AdminQueryFilter ludwigUnavailableAdminQueryFilter() {
        return new UnavailableAdminQueryFilter();
    }
}
