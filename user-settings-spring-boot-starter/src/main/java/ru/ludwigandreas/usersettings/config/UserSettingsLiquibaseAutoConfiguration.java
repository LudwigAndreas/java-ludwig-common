package ru.ludwigandreas.usersettings.config;

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
import org.springframework.context.annotation.Conditional;

/**
 * Applies this module's schema as a second, independent {@link SpringLiquibase}, alongside the
 * application's own changelog - the same pattern {@code outbox-spring-boot-starter} and
 * {@code identity-projection-spring-boot-starter} use, and for the same reasons.
 *
 * <p>Must run after {@link LiquibaseAutoConfiguration}, whose own bean is gated by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} <em>by type</em>: registering this one
 * first would silently suppress the application's own migrations. The guard here is by <em>name</em>
 * for the mirror-image reason - a type guard would make this bean suppress itself as soon as any
 * other {@code SpringLiquibase} exists.
 *
 * <p>Gated on a mode having been chosen, so a service that has the dependency but has configured
 * neither mode does not get three tables it will never use.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings.liquibase", name = "enabled", matchIfMissing = true)
@Conditional(SettingsModeCondition.class)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
@EnableConfigurationProperties(UserSettingsProperties.class)
public class UserSettingsLiquibaseAutoConfiguration {

    @Bean(name = "userSettingsLiquibase")
    @ConditionalOnMissingBean(name = "userSettingsLiquibase")
    public SpringLiquibase userSettingsLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/user-settings/user-settings-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
