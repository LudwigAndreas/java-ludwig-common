package ru.ludwigandreas.ingest.autoconfigure;

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
import ru.ludwigandreas.ingest.config.FileIngestProperties;

/**
 * Registers this module's schema as an independent {@link SpringLiquibase} bean, separate from the
 * consuming application's own {@code spring.liquibase.change-log}.
 *
 * <p>A copy of {@code JobCoreLiquibaseAutoConfiguration}, including the two details that are easy to
 * get wrong and silent when you do:
 *
 * <ul>
 *   <li>It <b>must</b> run after {@link LiquibaseAutoConfiguration}. That class's bean is gated by
 *       {@code @ConditionalOnMissingBean(SpringLiquibase.class)} <em>by type</em>, so registering this
 *       one first would suppress the application's own migrations entirely - the application would
 *       start, this module's tables would exist, and nothing else would.</li>
 *   <li>The guard here is by <b>name</b>, for the mirror-image reason: a type guard would make this
 *       bean suppress itself as soon as any other {@code SpringLiquibase} exists, which after the
 *       application's own is always.</li>
 * </ul>
 *
 * <p>Gated on {@code ludwig.ingest.liquibase.enabled} so that a service can fold this changelog into
 * its own master history instead, as {@code notification-service} does for the outbox and identity
 * schemas.
 */
@AutoConfiguration
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.ingest.liquibase", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(LiquibaseAutoConfiguration.class)
@EnableConfigurationProperties(FileIngestProperties.class)
public class FileIngestLiquibaseAutoConfiguration {

    /**
     * Applies {@code db/changelog/file-ingest/file-ingest-changelog.xml}.
     *
     * @param dataSource the application's data source
     * @return the module's Liquibase runner
     */
    @Bean(name = "fileIngestLiquibase")
    @ConditionalOnMissingBean(name = "fileIngestLiquibase")
    public SpringLiquibase fileIngestLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/file-ingest/file-ingest-changelog.xml");
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
