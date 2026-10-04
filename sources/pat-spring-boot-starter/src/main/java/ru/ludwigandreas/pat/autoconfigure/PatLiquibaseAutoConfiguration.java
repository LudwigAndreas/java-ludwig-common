package ru.ludwigandreas.pat.autoconfigure;

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
 * Registers this module's schema as an independent {@link SpringLiquibase} bean, separate from the
 * consuming application's own {@code spring.liquibase.change-log} - exactly as {@code job-core},
 * {@code outbox} and {@code idempotency} do.
 *
 * <p><b>Must</b> run after {@link LiquibaseAutoConfiguration}: that class's bean is gated by
 * {@code @ConditionalOnMissingBean(SpringLiquibase.class)} <em>by type</em>, so registering this one first
 * would silently suppress the application's own migrations. For the same reason the guard here is by
 * <em>name</em> - a type-based guard would make this bean suppress itself as soon as any other
 * {@code SpringLiquibase} exists, which is always.
 *
 * <p>Both failure modes are silent, which is why this comment exists in three modules rather than once: a
 * suppressed application changelog looks like a missing table at the first query, and a self-suppressed
 * module changelog looks like a missing table at the first token.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.pat.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
public class PatLiquibaseAutoConfiguration {

    /**
     * Applies {@code db/changelog/pat/pat-changelog.xml}.
     *
     * @param dataSource the application's data source
     * @return the module's Liquibase runner
     */
    @Bean(name = "ludwigPatLiquibase")
    @ConditionalOnMissingBean(name = "ludwigPatLiquibase")
    public SpringLiquibase ludwigPatLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/pat/pat-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
