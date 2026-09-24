package ru.ludwigandreas.reconciliation.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Registers this module's schema as an independent {@link SpringLiquibase} bean, alongside the
 * application's own.
 *
 * <p><b>Must</b> run after {@link LiquibaseAutoConfiguration}: that class's bean is gated by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} <em>by type</em>, so registering this one
 * first would silently suppress the application's own migrations. For the same reason the guard here
 * is by <em>name</em> - a type-based guard would make this bean suppress itself as soon as any other
 * {@code SpringLiquibase} exists, which is always.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.reconciliation.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationLiquibaseAutoConfiguration {

    /**
     * Applies {@code db/changelog/reconciliation/reconciliation-changelog.xml}.
     *
     * @param dataSource the application's data source
     * @return the module's Liquibase runner
     */
    @Bean(name = "reconciliationLiquibase")
    @ConditionalOnMissingBean(name = "reconciliationLiquibase")
    public SpringLiquibase reconciliationLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/reconciliation/reconciliation-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
