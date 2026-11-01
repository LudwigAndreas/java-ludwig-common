package ru.ludwigandreas.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.feed.ApplyOutcome;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.feed.IdentitySweep;
import ru.ludwigandreas.identity.feed.IdentitySweep.SweepResult;
import ru.ludwigandreas.identity.feed.UserProfile;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.principal.PrincipalDisabledEvent;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * Complete listings against the real tables: the set-based disable, the threshold, and source isolation.
 */
@SpringBootTest(classes = IdentitySweepIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "ludwig.security.jwt.enabled=false",
            "spring.liquibase.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "ludwig.identity.sources.staff.unmapped-roles=KEEP",
            "ludwig.identity.sources.customers.unmapped-roles=KEEP"})
@Import(PostgresContainerConfiguration.class)
class IdentitySweepIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    static final List<PrincipalDisabledEvent> DISABLED = new CopyOnWriteArrayList<>();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        /** What a consuming service declares, as crud-service-example's QuerydslConfig does. */
        @Bean
        JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
            return new JPAQueryFactory(entityManager);
        }

        @Bean
        Object disabledListener() {
            return new Object() {
                @EventListener
                void on(PrincipalDisabledEvent event) {
                    DISABLED.add(event);
                }
            };
        }
    }

    @Autowired
    private IdentityProjection projection;

    @Autowired
    private SecurityUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        users.deleteAll();
        jdbc.update("DELETE FROM identity_sweep_listed");
        DISABLED.clear();
    }

    private static IdentityChange.Upsert upsert(String source, String subject, Instant at) {
        return new IdentityChange.Upsert(source, subject, at, null,
                new UserProfile(subject, "t-1", Set.of("ORDER_AGENT"), null));
    }

    private List<String> seed(String source, int count) {
        List<String> subjects = IntStream.range(0, count).mapToObj(i -> source + "-" + i).toList();
        projection.applyAll(subjects.stream().map(subject -> upsert(source, subject, T0)).toList());
        return subjects;
    }

    private UserStatus status(String subject) {
        return users.findById(subject).orElseThrow().getStatus();
    }

    private int staged() {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM identity_sweep_listed", Integer.class);
        return rows == null ? 0 : rows;
    }

    @Test
    @DisplayName("a sweep listing 99 of 100 disables the unlisted one, announces it, and spares other sources")
    void disablesTheUnlisted() {
        List<String> staff = seed("staff", 100);
        List<String> customers = seed("customers", 3);

        IdentitySweep sweep = projection.beginSweep("staff");
        sweep.listed(staff.subList(1, 100));
        SweepResult result = sweep.complete();

        assertThat(result).isEqualTo(new SweepResult("staff", false, 100, 1, 1));
        assertThat(status("staff-0")).isEqualTo(UserStatus.DISABLED);
        assertThat(staff.subList(1, 100)).allMatch(subject -> status(subject) == UserStatus.ACTIVE);
        assertThat(customers).allMatch(subject -> status(subject) == UserStatus.ACTIVE);
        assertThat(DISABLED).containsExactly(new PrincipalDisabledEvent("staff-0", "sweep"));
        assertThat(staged()).isZero();
    }

    @Test
    @DisplayName("an empty listing is aborted past the threshold, and every user stays active")
    void abortsAnEmptyListing() {
        List<String> staff = seed("staff", 100);

        SweepResult result = projection.beginSweep("staff").complete();

        assertThat(result.aborted()).isTrue();
        assertThat(result.disabled()).isZero();
        assertThat(staff).allMatch(subject -> status(subject) == UserStatus.ACTIVE);
        assertThat(DISABLED).isEmpty();
        assertThat(staged()).isZero();
    }

    @Test
    @DisplayName("a user a sweep disabled is restored by the next upsert, even one older than their last change")
    void restoresASweptUser() {
        seed("staff", 20);
        IdentitySweep sweep = projection.beginSweep("staff");
        sweep.listed(IntStream.range(1, 20).mapToObj(i -> "staff-" + i).toList());
        sweep.complete();
        assertThat(status("staff-0")).isEqualTo(UserStatus.DISABLED);

        assertThat(projection.apply(upsert("staff", "staff-0", T0.minusSeconds(3600))))
                .isEqualTo(ApplyOutcome.APPLIED);

        assertThat(status("staff-0")).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("an abandoned sweep changes nothing and leaves nothing staged")
    void abandonChangesNothing() {
        seed("staff", 5);
        IdentitySweep sweep = projection.beginSweep("staff");
        sweep.listed(List.of("staff-1"));

        sweep.abandon();

        assertThat(IntStream.range(0, 5).mapToObj(i -> "staff-" + i))
                .allMatch(subject -> status(subject) == UserStatus.ACTIVE);
        assertThat(staged()).isZero();
    }

    @Test
    @DisplayName("the projection's write path works end to end: a foreign source is refused against the real table")
    void foreignSourceAgainstTheTable() {
        seed("staff", 1);

        assertThat(projection.apply(new IdentityChange.Disable("customers", "staff-0", T0.plusSeconds(1), null)))
                .isEqualTo(ApplyOutcome.FOREIGN_SOURCE);

        assertThat(status("staff-0")).isEqualTo(UserStatus.ACTIVE);
    }
}
