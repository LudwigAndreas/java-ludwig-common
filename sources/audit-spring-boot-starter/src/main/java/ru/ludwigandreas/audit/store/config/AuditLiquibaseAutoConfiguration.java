package ru.ludwigandreas.audit.store.config;

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
import ru.ludwigandreas.audit.config.AuditProperties;

/**
 * Registers this module's schema as an independent {@link SpringLiquibase} bean, separate from the
 * consuming application's own {@code spring.liquibase.change-log}, exactly as {@code job-core} and the
 * outbox module do.
 *
 * <p><b>Must</b> run after {@link LiquibaseAutoConfiguration}: that class's bean is gated by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} <em>by type</em>, so registering this one first
 * would silently suppress the application's own migrations. For the same reason the guard here is by
 * <em>name</em> - a type-based guard would make this bean suppress itself as soon as any other
 * {@code SpringLiquibase} exists, which is always.
 *
 * <h2>Why it must also run after the modules it migrates from</h2>
 *
 * <p>This changelog is the only one in the platform that reads another module's tables: the
 * {@code audit-002} and {@code audit-003} changesets move {@code user_setting_audit} and
 * {@code sync_audit_record} into {@code audit_event}. Both are guarded by a {@code tableExists}
 * precondition with {@code onFail="MARK_RAN"}, so that a service using neither module is not broken by
 * them - but that guard has a consequence: a changeset marked as ran is never reconsidered, so if this
 * changelog ran <em>before</em> the table existed the rows would never migrate and nothing would say so.
 *
 * <p>Ordering between two {@code SpringLiquibase} beans follows the registration order of the
 * autoconfigurations that declare them, which is otherwise undefined - so the modules whose tables this
 * one migrates are named below. By name rather than by class, because this module must not depend on
 * either of them: {@code user-settings-spring-boot-starter} depends on <em>this</em> one.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.audit.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(value = LiquibaseAutoConfiguration.class, name = {
        "ru.ludwigandreas.usersettings.config.UserSettingsLiquibaseAutoConfiguration",
        "ru.ludwigandreas.reconciliation.config.ReconciliationLiquibaseAutoConfiguration"})
@EnableConfigurationProperties(AuditProperties.class)
public class AuditLiquibaseAutoConfiguration {

    /**
     * Applies {@code db/changelog/audit/audit-changelog.xml}.
     *
     * @param dataSource the application's data source
     * @return the module's Liquibase runner
     */
    @Bean(name = "auditLiquibase")
    @ConditionalOnMissingBean(name = "auditLiquibase")
    public SpringLiquibase auditLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/audit/audit-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
