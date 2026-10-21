package ru.ludwigandreas.messaging.db.config;

import java.util.Map;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.messaging.db.settings.MessagingDbProperties;

/**
 * Installs the log's schema as its own {@link SpringLiquibase} bean, in the pattern
 * {@code OutboxLiquibaseAutoConfiguration} documents: after Boot's, guarded by name and not by type.
 *
 * <p>Every service carrying this module runs these changesets against the one shared database, each with
 * its own changelog table, so a service that has never recorded a changeset will run it against tables
 * another service already created. Every statement is therefore {@code IF NOT EXISTS}, and every changeset
 * first takes one transaction-scoped advisory lock, so two services starting at once cannot race each
 * other's {@code CREATE}.
 */
@AutoConfiguration(after = LiquibaseAutoConfiguration.class)
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(MessagingDbProperties.class)
public class MessagingDbLiquibaseAutoConfiguration {

    /** The changelog parameter carrying the schema name. */
    public static final String SCHEMA_PARAMETER = "messaging_db_schema";

    /**
     * The log's schema.
     *
     * @param dataSource the shared database
     * @param properties this module's configuration, for the schema name
     * @return the migration bean
     */
    @Bean(name = "messagingDbLiquibase")
    @ConditionalOnMissingBean(name = "messagingDbLiquibase")
    public SpringLiquibase messagingDbLiquibase(DataSource dataSource, MessagingDbProperties properties) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/messaging-db/messaging-db-changelog.xml");
        liquibase.setChangeLogParameters(Map.of(SCHEMA_PARAMETER, properties.getSchema()));
        liquibase.setShouldRun(true);
        return liquibase;
    }
}
