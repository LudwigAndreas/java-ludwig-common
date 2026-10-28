package ru.ludwigandreas.job.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLockAdministration;
import ru.ludwigandreas.job.core.lock.RunLockAdministration.RunLease;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * Forced release against the real lease table: it has exactly a release's effect, a live holder learns of it at
 * its next renewal, and an idle lease is left alone.
 */
class RunLockForceReleaseIT {

    private static final String LOCK_NAME = "outbox-poller";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(Containers.postgres().getJdbcUrl());
        source.setUser(Containers.postgres().getUsername());
        source.setPassword(Containers.postgres().getPassword());
        dataSource = source;

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/job-core/job-core-changelog.xml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    @BeforeEach
    void resetState() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM job_run_lock");
        }
    }

    private final RunLockAdministration administration = new RunLockAdministration(dataSource);

    @Test
    @DisplayName("a lease held by a dead pod is freed at once, and another instance acquires it without waiting")
    void deadHolder() {
        Optional<RunLockHandle> held = new JdbcRunLock(dataSource, "pod-1").tryAcquire(LOCK_NAME, LEASE);
        assertThat(held).isPresent();
        JdbcRunLock other = new JdbcRunLock(dataSource, "pod-2");
        assertThat(other.tryAcquire(LOCK_NAME, LEASE)).isEmpty();

        Optional<RunLease> previous = administration.forceRelease(LOCK_NAME);

        assertThat(previous).isPresent();
        assertThat(previous.get().owner()).isEqualTo("pod-1");
        assertThat(previous.get().runId()).isEqualTo(held.get().runId());
        RunLease now = administration.lease(LOCK_NAME).orElseThrow();
        assertThat(now.owner()).isNull();
        assertThat(now.heldAt(Instant.now())).isFalse();
        assertThat(other.tryAcquire(LOCK_NAME, LEASE)).isPresent();
    }

    @Test
    @DisplayName("a live holder's next renewal fails, so it stops, and only the new holder continues")
    void liveHolder() {
        RunLockHandle first = new JdbcRunLock(dataSource, "pod-1").tryAcquire(LOCK_NAME, LEASE).orElseThrow();

        administration.forceRelease(LOCK_NAME);
        RunLockHandle second = new JdbcRunLock(dataSource, "pod-2").tryAcquire(LOCK_NAME, LEASE).orElseThrow();

        assertThat(first.renew(LEASE)).isFalse();
        assertThat(second.renew(LEASE)).isTrue();
        assertThat(administration.lease(LOCK_NAME).orElseThrow().owner()).isEqualTo("pod-2");
    }

    @Test
    @DisplayName("an idle lease is left unchanged, and the release says it held nothing")
    void idleLease() {
        new JdbcRunLock(dataSource, "pod-1").tryAcquire(LOCK_NAME, LEASE).orElseThrow().close();
        RunLease before = administration.lease(LOCK_NAME).orElseThrow();

        assertThat(administration.forceRelease(LOCK_NAME)).isEmpty();
        assertThat(administration.forceRelease("never-taken")).isEmpty();
        assertThat(administration.lease(LOCK_NAME)).contains(before);
    }

    @Test
    @DisplayName("every lease row is listed, held or not, ordered by name")
    void listsLeases() {
        new JdbcRunLock(dataSource, "pod-1").tryAcquire("b-job", LEASE).orElseThrow();
        new JdbcRunLock(dataSource, "pod-1").tryAcquire("a-job", LEASE).orElseThrow().close();

        assertThat(administration.leases()).extracting(RunLease::lockName).containsExactly("a-job", "b-job");
        assertThat(administration.leases()).filteredOn(lease -> lease.heldAt(Instant.now()))
                .extracting(RunLease::lockName).containsExactly("b-job");
    }
}
