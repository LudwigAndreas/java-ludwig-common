package ru.ludwigandreas.audit.store.integration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * Puts the two legacy audit tables, with their rows, into the database <em>before</em> Spring starts.
 *
 * <h2>Why an initializer rather than the container's init script</h2>
 *
 * <p>This used to be {@code withInitScript("db/legacy/legacy-audit-tables.sql")} on a container this test
 * class owned. It cannot stay that way now that the container is
 * {@link ru.ludwigandreas.testsupport.container.Containers#postgres()}, shared across the whole JVM: an
 * init script runs once, when the container starts, and the shared container is started by whichever
 * suite touches it first - which may not be this one, and may be in another module entirely.
 *
 * <p>An {@code ApplicationContextInitializer} preserves the ordering that is the whole point of the
 * fixture. It runs before any bean is instantiated, so it runs before this module's {@code SpringLiquibase}
 * bean - which is the requirement: {@code user_setting_audit} and {@code sync_audit_record} were created by
 * a <em>previous</em> release and are already in the database, with rows, before this release's migration
 * looks for them. Creating them from a changelog instead would create them after the migration had already
 * run, which is the brand-new-database case and has no rows to migrate at all.
 *
 * <h2>Why it drops first</h2>
 *
 * <p>The shared container outlives the context, and Spring may build the context more than once per JVM
 * (a different context cache key, a dirtied context). Re-running the script against tables that are
 * already there would fail on the {@code CREATE}, and re-inserting would fail on the primary key. Dropping
 * first makes the fixture idempotent and gives every context the same pristine "previous release" state -
 * which also matters because this module's migration consumes those rows.
 */
class LegacyAuditTablesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("db/legacy/drop-legacy-audit-tables.sql"));
        populator.addScript(new ClassPathResource("db/legacy/legacy-audit-tables.sql"));
        populator.execute(dataSource);
    }
}
