package ru.ludwigandreas.identity.directory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.identity.directory.service.DirectoryFeed;
import ru.ludwigandreas.identity.directory.service.DirectoryJob;
import ru.ludwigandreas.identity.directory.DirectoryPage;
import ru.ludwigandreas.identity.directory.DirectoryQuery;
import ru.ludwigandreas.identity.directory.service.DirectorySync;
import ru.ludwigandreas.identity.directory.IdentityDirectory;
import ru.ludwigandreas.identity.directory.repository.DirectoryState;
import ru.ludwigandreas.identity.directory.repository.DirectoryStateStore;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.UserProfile;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * Directory syncs and sweeps against the real tables, with an in-memory provider that pages, filters by change and
 * fails on demand.
 */
@SpringBootTest(classes = DirectorySyncIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "ludwig.security.jwt.enabled=false",
            "spring.liquibase.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "ludwig.identity.sources.staff.unmapped-roles=KEEP",
            "ludwig.identity.sources.customers.unmapped-roles=KEEP",
            "ludwig.identity.directory.sources.staff.page-size=2",
            "ludwig.identity.directory.sources.customers.page-size=2",
            // The schedules are exercised by calling the jobs; their own timers must not race the assertions.
            "ludwig.identity.directory.sources.staff.initial-delay=PT10H",
            "ludwig.identity.directory.sources.customers.initial-delay=PT10H",
            "ludwig.identity.directory.sources.staff.sweep-interval=PT10H",
            "ludwig.identity.directory.sources.customers.sweep-interval=PT10H"})
@Import(PostgresContainerConfiguration.class)
class DirectorySyncIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        /** What a consuming service declares, as crud-service-example's QuerydslConfig does. */
        @Bean
        JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
            return new JPAQueryFactory(entityManager);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        FakeDirectory staffDirectory() {
            return new FakeDirectory("staff");
        }

        @Bean
        FakeDirectory customersDirectory() {
            return new FakeDirectory("customers");
        }
    }

    /** A provider that pages its users in change order, filters by change, and fails on a chosen page. */
    static final class FakeDirectory implements IdentityDirectory {

        record Entry(String subject, Instant changedAt, boolean active) {
        }

        private final String source;
        final List<Entry> users = new CopyOnWriteArrayList<>();
        final List<DirectoryQuery> queries = new CopyOnWriteArrayList<>();
        volatile Integer failOnPage;

        FakeDirectory(String source) {
            this.source = source;
        }

        void reset() {
            users.clear();
            queries.clear();
            failOnPage = null;
        }

        void add(String subject, Instant changedAt) {
            users.removeIf(entry -> entry.subject().equals(subject));
            users.add(new Entry(subject, changedAt, true));
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public DirectoryPage fetch(DirectoryQuery query) {
            queries.add(query);
            int page = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
            if (failOnPage != null && failOnPage == page) {
                throw new IllegalStateException("provider down");
            }
            List<Entry> selected = users.stream()
                    .filter(entry -> query.changedSince() == null || !entry.changedAt().isBefore(query.changedSince()))
                    .sorted(Comparator.comparing(Entry::changedAt)).toList();
            int from = page * query.pageSize();
            int to = Math.min(from + query.pageSize(), selected.size());
            List<Entry> slice = from >= selected.size() ? List.of() : selected.subList(from, to);
            List<IdentityChange> changes = slice.stream().map(entry -> (IdentityChange) (entry.active()
                    ? new IdentityChange.Upsert(source, entry.subject(), entry.changedAt(), null,
                            new UserProfile(entry.subject(), "t-1", Set.of("ORDER_AGENT"), null))
                    : new IdentityChange.Disable(source, entry.subject(), entry.changedAt(), null))).toList();
            String next = to < selected.size() ? String.valueOf(page + 1) : null;
            return new DirectoryPage(changes, next, slice.isEmpty() ? null : slice.get(slice.size() - 1).changedAt());
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("staffDirectory")
    private FakeDirectory staff;

    @Autowired
    @Qualifier("customersDirectory")
    private FakeDirectory customers;

    @Autowired
    private SecurityUserRepository users;

    @Autowired
    private DirectoryStateStore state;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RunLock lock;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void reset() {
        staff.reset();
        customers.reset();
        users.deleteAll();
        jdbc.update("DELETE FROM identity_directory_state");
        jdbc.update("DELETE FROM identity_sweep_listed");
    }

    private DirectorySync sync(String directoryBean) {
        return context.getBean("identityDirectorySync." + directoryBean, DirectorySync.class);
    }

    private void seed(FakeDirectory directory, int count) {
        IntStream.range(0, count).forEach(i -> directory.add(directory.source() + "-" + i, T0.plusSeconds(i)));
    }

    private UserStatus status(String subject) {
        return users.findById(subject).orElseThrow().getStatus();
    }

    private DirectoryState stateOf(String source) {
        return state.read(source).orElseThrow();
    }

    @Test
    @DisplayName("the first sync of a source with no watermark lists it completely and stores where it ended")
    void firstSyncSweeps() {
        seed(staff, 5);

        sync("staffDirectory").sync();

        assertThat(IntStream.range(0, 5).mapToObj(i -> "staff-" + i)).allMatch(s -> status(s) == UserStatus.ACTIVE);
        assertThat(staff.queries).allMatch(query -> query.changedSince() == null).hasSize(3);
        assertThat(stateOf("staff").watermark()).isEqualTo(T0.plusSeconds(4));
        assertThat(stateOf("staff").lastSweepOutcome()).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("a sync interrupted after two pages resumes from the second page's watermark")
    void resumesAfterAnInterruptedPage() {
        seed(staff, 1);
        sync("staffDirectory").sync();
        Instant w0 = stateOf("staff").watermark();
        IntStream.rangeClosed(1, 5).forEach(i -> staff.add("changed-" + i, w0.plusSeconds(60L * i)));
        staff.queries.clear();
        staff.failOnPage = 2;

        assertThatThrownBy(() -> sync("staffDirectory").sync()).isInstanceOf(IllegalStateException.class);

        // The listing from w0 includes the boundary user at w0 itself, so pages 1 and 2 end at changed-3.
        assertThat(stateOf("staff").watermark()).isEqualTo(w0.plusSeconds(180));
        assertThat(users.findById("changed-3")).isPresent();
        assertThat(users.findById("changed-4")).isEmpty();
        assertThat(stateOf("staff").lastSyncOutcome()).isEqualTo("failed");

        staff.failOnPage = null;
        staff.queries.clear();
        sync("staffDirectory").sync();

        assertThat(staff.queries.get(0).changedSince()).isEqualTo(w0.plusSeconds(180));
        assertThat(users.findById("changed-5")).isPresent();
        assertThat(stateOf("staff").lastSyncOutcome()).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("a failing provider keeps its watermark and users, records the error type, and stops no other source")
    void failureIsIsolated() {
        seed(staff, 2);
        seed(customers, 2);
        sync("staffDirectory").sync();
        Instant watermark = stateOf("staff").watermark();
        staff.add("later", watermark.plusSeconds(60));
        staff.failOnPage = 0;

        assertThatThrownBy(() -> sync("staffDirectory").sync()).isInstanceOf(IllegalStateException.class);
        sync("customersDirectory").sync();

        assertThat(stateOf("staff").watermark()).isEqualTo(watermark);
        assertThat(stateOf("staff").lastSyncError()).isEqualTo(IllegalStateException.class.getName());
        assertThat(users.findById("later")).isEmpty();
        assertThat(status("customers-1")).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("a sweep disables a user the provider no longer lists")
    void sweepDisablesTheUnlisted() {
        seed(staff, 20);
        sync("staffDirectory").sync();
        staff.users.removeIf(entry -> entry.subject().equals("staff-3"));

        sync("staffDirectory").sweep();

        assertThat(status("staff-3")).isEqualTo(UserStatus.DISABLED);
        assertThat(status("staff-4")).isEqualTo(UserStatus.ACTIVE);
        assertThat(stateOf("staff").lastSweepDisabled()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sweep that fails on its last page changes no user's status")
    void failedSweepChangesNothing() {
        seed(staff, 20);
        sync("staffDirectory").sync();
        staff.users.removeIf(entry -> entry.subject().equals("staff-3"));
        staff.failOnPage = 9;

        assertThatThrownBy(() -> sync("staffDirectory").sweep()).isInstanceOf(IllegalStateException.class);

        assertThat(status("staff-3")).isEqualTo(UserStatus.ACTIVE);
        assertThat(stateOf("staff").lastSweepOutcome()).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_sweep_listed", Integer.class)).isZero();
    }

    @Test
    @DisplayName("each source runs as two platform jobs, and a held run lock keeps another replica out")
    void jobsRunUnderTheirLock() {
        assertThat(context.getBeansOfType(SelfSchedulingLifecycle.class).values())
                .extracting(SelfSchedulingLifecycle::jobName)
                .contains("ludwig-identity-staff-sync", "ludwig-identity-staff-sweep",
                        "ludwig-identity-customers-sync", "ludwig-identity-customers-sweep");
        seed(staff, 2);
        DirectoryJob job = context.getBean("identityDirectorySyncJob.staffDirectory", DirectoryJob.class);

        Optional<RunLockHandle> held = lock.tryAcquire("ludwig-identity-staff-sync", Duration.ofMinutes(1));
        try {
            assertThat(held).isPresent();
            job.runNow();
            assertThat(staff.queries).isEmpty();
        } finally {
            held.ifPresent(RunLockHandle::close);
        }

        job.runNow();
        assertThat(staff.queries).isNotEmpty();
    }

    @Test
    @DisplayName("progress is visible through the feed and timed per source, kind and outcome")
    void progressIsObservable() {
        double syncs = runs("sync");
        double sweeps = runs("sweep");
        seed(staff, 3);
        sync("staffDirectory").sync();
        staff.add("later", T0.plusSeconds(600));
        sync("staffDirectory").sync();

        Map<String, Object> status = context.getBean("identityDirectoryFeed.staffDirectory", DirectoryFeed.class)
                .status();
        assertThat(status).containsEntry("watermark", T0.plusSeconds(600));
        assertThat(status.get("lastSync")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("outcome", "succeeded");
        assertThat(runs("sync") - syncs).isEqualTo(1);
        assertThat(runs("sweep") - sweeps).isEqualTo(1);
    }

    /** Succeeded runs of {@code staff} so far; the registry is shared by every test of this context. */
    private double runs(String kind) {
        Timer timer = meters.find("ludwig.identity.directory.run").tag("source", "staff").tag("kind", kind)
                .tag("outcome", "succeeded").timer();
        return timer == null ? 0 : timer.count();
    }
}
