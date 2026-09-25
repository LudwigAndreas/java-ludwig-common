package ru.ludwigandreas.export.config;

import javax.sql.DataSource;
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

/**
 * Registers this module's schema as a second, independent {@link SpringLiquibase} bean, separate
 * from the consuming application's own {@code spring.liquibase.change-log}.
 *
 * <p>Several {@code SpringLiquibase} beans sharing one {@code DATABASECHANGELOG} table -
 * differentiated by changeset id and author, which is why this module namespaces both - is a
 * supported Spring Boot pattern; {@code LiquibaseSchemaManagementProvider} is built from an
 * {@code ObjectProvider<SpringLiquibase>} and is plural-aware by design.
 *
 * <p><b>Must</b> run after {@link LiquibaseAutoConfiguration}. That class's bean is gated by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} <em>by type</em>, so registering this one
 * first would silently suppress the application's own migrations. For the same reason the guard here
 * is by name: a type-based guard would make this bean suppress itself the moment any other
 * {@code SpringLiquibase} exists, which is exactly the situation it is meant to coexist with.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.export.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
@EnableConfigurationProperties(ExportProperties.class)
public class ExportLiquibaseAutoConfiguration {

    @Bean(name = "exportLiquibase")
    @ConditionalOnMissingBean(name = "exportLiquibase")
    public SpringLiquibase exportLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/export/export-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
