package ru.ludwigandreas.identity.directory.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Applies {@code identity_directory_state}'s changelog as an independent {@code SpringLiquibase}, as the projection
 * does its own; off with {@code ludwig.identity.directory.liquibase.enabled=false} when the application includes it.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.identity.directory.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
public class IdentityDirectoryLiquibaseAutoConfiguration {

    @Bean(name = "identityDirectoryLiquibase")
    @ConditionalOnMissingBean(name = "identityDirectoryLiquibase")
    public SpringLiquibase identityDirectoryLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/identity-directory/identity-directory-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
