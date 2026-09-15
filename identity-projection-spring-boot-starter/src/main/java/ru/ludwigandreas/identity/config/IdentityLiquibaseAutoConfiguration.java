package ru.ludwigandreas.identity.config;

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
 * Applies this module's schema as a second, independent {@link SpringLiquibase}, alongside the
 * application's own changelog - the same pattern {@code outbox-spring-boot-starter} uses, and for the same
 * reasons.
 *
 * <p>Must run after {@link LiquibaseAutoConfiguration}, whose own bean is gated by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} <em>by type</em>: registering this one first
 * would silently suppress the application's own migrations. The guard here is by <em>name</em> for the
 * mirror-image reason - a type guard would make this bean suppress itself as soon as any other
 * {@code SpringLiquibase} exists.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.identity.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
@EnableConfigurationProperties(IdentityProjectionProperties.class)
public class IdentityLiquibaseAutoConfiguration {

    @Bean(name = "identityLiquibase")
    @ConditionalOnMissingBean(name = "identityLiquibase")
    public SpringLiquibase identityLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/identity/identity-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
