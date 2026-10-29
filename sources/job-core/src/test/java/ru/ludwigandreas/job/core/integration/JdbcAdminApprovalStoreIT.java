package ru.ludwigandreas.job.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import ru.ludwigandreas.job.core.approval.JdbcAdminApprovalStore;
import ru.ludwigandreas.security.admin.AdminApproval;
import ru.ludwigandreas.security.admin.AdminApprovalState;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.testsupport.container.Containers;

/** The approval store on PostgreSQL: round trip, listing, one winner under a race, and the sweep's two moves. */
class JdbcAdminApprovalStoreIT {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private static DataSource dataSource;
    private JdbcAdminApprovalStore store;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(Containers.postgres().getJdbcUrl());
        source.setUser(Containers.postgres().getUsername());
        source.setPassword(Containers.postgres().getPassword());
        source.setSslMode("disable");
        dataSource = source;
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/job-core/job-core-changelog.xml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    @BeforeEach
    void empty() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM job_admin_approval");
        }
        store = new JdbcAdminApprovalStore(dataSource);
    }

    private static AdminApproval pending(String operation, Instant requestedAt, Duration ttl) {
        return new AdminApproval(UUID.randomUUID(), operation,
                new AdminApproval.StoredRequest("POST", "/admin/v1/outbox/messages/m-1/discard", "dry=false",
                        "application/json", "ru", "INC-7", "{\"note\":\"x\"}".getBytes(StandardCharsets.UTF_8)),
                "alice", PrincipalType.USER, requestedAt, requestedAt.plus(ttl), AdminApprovalState.PENDING, null,
                null, null);
    }

    @Test
    @DisplayName("an approval round-trips, and reaching a terminal state drops its body but keeps the outcome")
    void roundTrip() {
        AdminApproval approval = pending("outbox.privileged", NOW, Duration.ofHours(24));
        store.create(approval);

        AdminApproval read = store.find(approval.id()).orElseThrow();
        assertThat(read.request().body()).isEqualTo(approval.request().body());
        assertThat(read.request().query()).isEqualTo("dry=false");
        assertThat(read.requesterType()).isEqualTo(PrincipalType.USER);
        assertThat(read.requestedAt()).isEqualTo(NOW);

        assertThat(store.transition(approval.id(), AdminApprovalState.PENDING, AdminApprovalState.APPROVING, "bob",
                NOW, null)).isTrue();
        assertThat(store.find(approval.id()).orElseThrow().request().body()).isNotEmpty();
        assertThat(store.transition(approval.id(), AdminApprovalState.APPROVING, AdminApprovalState.EXECUTED, null,
                NOW, new AdminApproval.Outcome(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8))))
                .isTrue();
        AdminApproval executed = store.find(approval.id()).orElseThrow();
        assertThat(executed.request().body()).isEmpty();
        assertThat(executed.decidedBy()).isEqualTo("bob");
        assertThat(executed.outcome().status()).isEqualTo(200);
        assertThat(store.transition(approval.id(), AdminApprovalState.PENDING, AdminApprovalState.REJECTED, "carol",
                NOW, null)).isFalse();
    }

    @Test
    @DisplayName("the list keeps to the given operations and state, newest first, paged by keyset")
    void listing() {
        store.create(pending("outbox.privileged", NOW.minusSeconds(30), Duration.ofHours(1)));
        AdminApproval middle = pending("outbox.privileged", NOW.minusSeconds(20), Duration.ofHours(1));
        store.create(middle);
        AdminApproval newest = pending("outbox.privileged", NOW.minusSeconds(10), Duration.ofHours(1));
        store.create(newest);
        store.create(pending("identity.privileged", NOW, Duration.ofHours(1)));

        List<AdminApproval> first = store.list(List.of("outbox.privileged"), AdminApprovalState.PENDING, null, null, 2);
        List<AdminApproval> second = store.list(List.of("outbox.privileged"), null, middle.requestedAt(),
                middle.id(), 2);

        assertThat(first).extracting(AdminApproval::id).containsExactly(newest.id(), middle.id());
        assertThat(second).hasSize(1).allSatisfy(each -> assertThat(each.requestedAt()).isBefore(middle.requestedAt()));
        assertThat(store.list(List.of(), null, null, null, 10)).isEmpty();
        assertThat(store.list(List.of("outbox.privileged"), AdminApprovalState.EXECUTED, null, null, 10)).isEmpty();
    }

    @Test
    @DisplayName("of eight approvers racing on one approval, exactly one moves it")
    void oneWinner() throws Exception {
        AdminApproval approval = pending("outbox.privileged", NOW, Duration.ofHours(24));
        store.create(approval);
        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String approver = "approver-" + i;
                Callable<Boolean> race = () -> {
                    start.await();
                    return store.transition(approval.id(), AdminApprovalState.PENDING, AdminApprovalState.APPROVING,
                            approver, Instant.now(), null);
                };
                results.add(pool.submit(race));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the sweep expires undecided approvals and interrupts executions left behind, reporting each")
    void sweep() {
        AdminApproval stale = pending("outbox.privileged", NOW.minus(Duration.ofHours(25)), Duration.ofHours(24));
        AdminApproval fresh = pending("outbox.privileged", NOW, Duration.ofHours(24));
        AdminApproval stuck = pending("outbox.privileged", NOW.minus(Duration.ofHours(1)), Duration.ofHours(24));
        store.create(stale);
        store.create(fresh);
        store.create(stuck);
        store.transition(stuck.id(), AdminApprovalState.PENDING, AdminApprovalState.APPROVING, "bob",
                NOW.minus(Duration.ofMinutes(10)), null);

        assertThat(store.expire(NOW)).extracting(AdminApproval::id).containsExactly(stale.id());
        assertThat(store.interrupt(NOW.minus(Duration.ofMinutes(5)))).extracting(AdminApproval::id)
                .containsExactly(stuck.id());
        assertThat(store.find(stale.id()).orElseThrow().state()).isEqualTo(AdminApprovalState.EXPIRED);
        assertThat(store.find(stale.id()).orElseThrow().request().body()).isEmpty();
        assertThat(store.find(stuck.id()).orElseThrow().state()).isEqualTo(AdminApprovalState.INTERRUPTED);
        assertThat(store.find(fresh.id()).orElseThrow().state()).isEqualTo(AdminApprovalState.PENDING);
        assertThat(store.expire(NOW)).isEmpty();
    }
}
