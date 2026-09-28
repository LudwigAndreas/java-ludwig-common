package ru.ludwigandreas.outbox.config;

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
 * Registers this module's own schema as a second, independent {@link SpringLiquibase} bean, separate
 * from the consuming application's own {@code spring.liquibase.change-log}. Multiple {@code SpringLiquibase}
 * beans sharing one {@code DATABASECHANGELOG} tracking table (differentiated by changeset id+author) is
 * a supported Spring Boot pattern - see {@code LiquibaseSchemaManagementProvider}, which is built from an
 * {@code ObjectProvider<SpringLiquibase>}, i.e. plural-aware by design.
 * <p>
 * <b>Must</b> run after {@link LiquibaseAutoConfiguration}: that class's own bean is gated by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} (by <em>type</em>), so registering our bean
 * first would silently suppress the consuming application's own migrations. For the same reason, our own
 * guard below is by <em>name</em> ({@code @ConditionalOnMissingBean(name = "outboxLiquibase")}), not type -
 * a type-based guard here would make this bean suppress itself the moment any {@code SpringLiquibase}
 * bean (including the application's own) exists, which is never what's wanted.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.outbox.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxLiquibaseAutoConfiguration {

    @Bean(name = "outboxLiquibase")
    @ConditionalOnMissingBean(name = "outboxLiquibase")
    public SpringLiquibase outboxLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/outbox/outbox-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
