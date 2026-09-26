package ru.ludwigandreas.audit.store.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.AuditWriteFailedException;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;
import ru.ludwigandreas.audit.store.repository.AuditTrailQuery;

/**
 * The trail against a real PostgreSQL, with the shipped Liquibase migrations and Hibernate validating its
 * mappings against them.
 *
 * <p>Everything asserted here is behaviour a service depends on and that a unit test cannot reach: that the
 * append-only guarantee is enforced rather than conventional, that the two legacy tables' rows actually
 * arrive and reconcile against their source counts, that the old redaction marker is rewritten, and that the
 * read side's predicates compile to SQL that means what they say.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.application.name=audit-test",
        // The retention job is driven by hand here; a six-hour schedule would never fire inside a test,
        // and letting it fire on its own would race the assertions about what is in the table.
        "ludwig.audit.retention.enabled=false",
        // Stands in for the consuming application's own changelog; the legacy tables are created by the
        // container's init script instead, before Spring starts - see that file for why.
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class AuditStoreIntegrationTest {

    /**
     * Pinned by name, version <em>and</em> digest.
     *
     * <p>A tag alone can be re-pointed at different content, so a test would silently change what it
     * executes. Company policy is name plus version plus digest, everywhere, and a test container is not an
     * exception to it.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:"
                    + "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            // The legacy tables and their rows, created before the application boots, because that is
            // what a real upgrade looks like: they were created by a previous release and this release's
            // Liquibase finds them already there. Creating them from a changelog instead would create
            // them after this module's migration had already looked for them.
            .withInitScript("db/legacy/legacy-audit-tables.sql");

    @Autowired
    private AuditSink sink;

    @Autowired
    private AuditEventRepository events;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("an event recorded through the platform sink is readable from audit_event")
    void recordsAnEventThroughTheWholeStack() {
        UUID id = UUID.randomUUID();
        sink.record(AuditEvent.builder()
                .id(id)
                .category(AuditCategories.SETTINGS)
                .action("setting.set")
                .actor(Actor.of("alice", "USER").onBehalfOf("bob"))
                .resource(new Resource("setting", "ui.page-size", "Page size"))
                .outcome(AuditOutcome.success())
                .correlationId("corr-live")
                .attributes(Map.of("oldValue", "50", "newValue", "75"))
                .build());

        AuditEventEntity stored = events.getByIdOrThrow(id);
        assertThat(stored.getCategory()).isEqualTo("settings");
        assertThat(stored.getAction()).isEqualTo("setting.set");
        assertThat(stored.getActorSubject()).isEqualTo("alice");
        assertThat(stored.getOnBehalfOf()).isEqualTo("bob");
        assertThat(stored.getResourceId()).isEqualTo("ui.page-size");
        assertThat(stored.getOutcome()).isEqualTo("SUCCESS");
        assertThat(stored.getAttributes()).containsEntry("newValue", "75");
        // The deployment that wrote the row, which matters because this table can also receive events
        // relayed from another service.
        assertThat(stored.getSourceSystem()).isEqualTo("audit-test");
    }

    /**
     * Append-only, enforced rather than conventional. {@code db-core}'s {@code SnapshotImmutabilityListener}
     * is what does it, reused rather than a second immutability mechanism being invented.
     */
    @Test
    @DisplayName("an attempt to update an audit_event row is rejected")
    void rejectsAnUpdate() {
        UUID id = UUID.randomUUID();
        sink.record(AuditEvent.builder().id(id).category(AuditCategories.ACCESS)
                .action("access.denied").build());
        AuditEventEntity stored = events.getByIdOrThrow(id);
        stored.setReason("rewritten after the fact");

        assertThatThrownBy(() -> events.saveAndFlush(stored))
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("rows from user_setting_audit land in audit_event, reconciling with the source count")
    void migratesTheSettingsTrail() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer source = jdbc.queryForObject("SELECT count(*) FROM user_setting_audit", Integer.class);

        // Bounded on both sides, at the window the fixture's rows actually occupy: an open lower bound
        // would let any later test that records an old settings event change what this one counts, and
        // the assertion below is a reconciliation against the source table rather than a spot check.
        List<AuditEventEntity> migrated = events.find(AuditTrailQuery.builder()
                .categories(List.of(AuditCategories.SETTINGS))
                .from(Instant.parse("2020-01-01T00:00:00Z"))
                .to(Instant.parse("2020-02-01T00:00:00Z"))
                .build());

        // They must reconcile: a migration that starts the new trail with fewer rows than the old one has
        // moved the auditor's problem rather than solved it.
        assertThat(migrated).hasSize(source);

        AuditEventEntity set = byResourceId(migrated, "ui.page-size");
        assertThat(set.getAction()).isEqualTo("setting.set");
        assertThat(set.getActorSubject()).isEqualTo("user-1");
        assertThat(set.getOnBehalfOf()).isEqualTo("user-1");
        assertThat(set.getAttributes())
                .containsEntry("tenantId", "acme")
                .containsEntry("oldValue", "50")
                .containsEntry("newValue", "75")
                .containsEntry("scopeType", "USER");

        // ADMIN_READ is the row whose actor and subject genuinely differ, and the one the old table's
        // column names made ambiguous.
        AuditEventEntity read = migrated.stream()
                .filter(event -> "setting.admin-read".equals(event.getAction()))
                .findFirst().orElseThrow();
        assertThat(read.getActorSubject()).isEqualTo("admin-1");
        assertThat(read.getOnBehalfOf()).isEqualTo("user-1");
    }

    /**
     * The reason picking one marker was a data migration and not a constant change: without this, the table
     * spells one concept two ways and no query can tell "redacted under the old rule" from "a user whose
     * value is literally the string {@code [redacted]}".
     */
    @Test
    @DisplayName("the old [redacted] marker is rewritten to the platform's")
    void migratesTheRedactionMarker() {
        List<AuditEventEntity> migrated = events.find(AuditTrailQuery.builder()
                .categories(List.of(AuditCategories.SETTINGS))
                .resourceType("setting")
                .resourceId("profile.mobile")
                .from(Instant.parse("2020-01-01T00:00:00Z"))
                .to(Instant.parse("2020-02-01T00:00:00Z"))
                .build());

        assertThat(migrated).hasSize(1);
        assertThat(migrated.get(0).getAttributes())
                .containsEntry("redacted", true)
                .containsEntry("oldValue", Redaction.MASK)
                .containsEntry("newValue", Redaction.MASK);
        assertThat(migrated.get(0).getAttributes().values())
                .doesNotContain(Redaction.LEGACY_SETTINGS_MASK);
    }

    @Test
    @DisplayName("rows from sync_audit_record land in audit_event with the Category preserved")
    void migratesTheReconciliationTrail() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer source = jdbc.queryForObject("SELECT count(*) FROM sync_audit_record", Integer.class);

        List<AuditEventEntity> migrated = events.find(AuditTrailQuery.builder()
                .categories(List.of(AuditCategories.RECONCILIATION))
                .build());

        assertThat(migrated).hasSize(source);

        AuditEventEntity quarantined = migrated.stream()
                .filter(event -> "record.quarantined".equals(event.getAction()))
                .findFirst().orElseThrow();
        // Category becomes an attribute, not audit_event.category: that column names the subsystem, and
        // these five name a kind of event within it.
        assertThat(quarantined.getAttributes())
                .containsEntry("eventCategory", "RECORD")
                .containsEntry("fromState", "STAGED")
                .containsEntry("toState", "QUARANTINED")
                .containsEntry("instance", "pod-a");
        assertThat(quarantined.getResourceId()).isEqualTo("partner-sync");
        assertThat(quarantined.getReason()).isEqualTo("checksum mismatch");

        // An operator event keeps the operator; everything else is system.
        AuditEventEntity operator = migrated.stream()
                .filter(event -> "operator.retry".equals(event.getAction()))
                .findFirst().orElseThrow();
        assertThat(operator.getActorSubject()).isEqualTo("ops-1");
        assertThat(quarantined.getActorSubject()).isEqualTo(Actor.SYSTEM);
    }

    /**
     * The end of the FAIL_OPERATION path, through real wiring: a settings event whose write fails has to
     * reach the caller as the type the problem pipeline renders as a 503, not as a persistence exception
     * about data that was never written.
     */
    @Test
    @DisplayName("a settings event that cannot be written fails the caller with AuditWriteFailedException")
    void failsAStateMutationWithTheMappedException() {
        // A duplicate id is the one way to make the real JPA sink fail without breaking the schema: the
        // primary key is the event id, which is what makes a redelivery a collision rather than a copy.
        UUID id = UUID.randomUUID();
        sink.record(AuditEvent.builder().id(id).category(AuditCategories.SETTINGS)
                .action("setting.set").build());

        assertThatThrownBy(() -> sink.record(AuditEvent.builder().id(id)
                .category(AuditCategories.SETTINGS).action("setting.set").build()))
                .isInstanceOf(AuditWriteFailedException.class);
    }

    @Test
    @DisplayName("the read side filters by actor, category, outcome and time window")
    void readsTheTrailByCriteria() {
        String actor = "reader-" + UUID.randomUUID();
        Instant base = Instant.parse("2030-01-01T00:00:00Z");
        sink.record(AuditEvent.builder().category(AuditCategories.ACCESS).action("access.denied")
                .actor(Actor.of(actor)).occurredAt(base)
                .outcome(AuditOutcome.denied("out-of-scope")).build());
        sink.record(AuditEvent.builder().category(AuditCategories.ACCESS).action("access.granted")
                .actor(Actor.of(actor)).occurredAt(base.plus(1, ChronoUnit.HOURS)).build());

        assertThat(events.find(AuditTrailQuery.builder().actorSubject(actor).build())).hasSize(2);
        assertThat(events.find(AuditTrailQuery.builder().actorSubject(actor)
                .outcomes(List.of("DENIED")).build())).hasSize(1);
        assertThat(events.find(AuditTrailQuery.builder().actorSubject(actor)
                .from(base.plus(30, ChronoUnit.MINUTES)).build())).hasSize(1);
        assertThat(events.find(AuditTrailQuery.builder().actorSubject(actor)
                .categories(List.of(AuditCategories.EXPORT)).build())).isEmpty();
        // Newest first, which is the order an auditor reads a trail in.
        assertThat(events.find(AuditTrailQuery.builder().actorSubject(actor).build()).get(0).getAction())
                .isEqualTo("access.granted");
    }

    /**
     * The purge removes only what is past its cutoff. Driven through the repository rather than the job,
     * because the job's own scheduling and locking are {@code job-core}'s tested behaviour and what is
     * this module's is the statement.
     */
    @Test
    @DisplayName("a purge removes only events older than the cutoff, and only in the named category")
    void purgesOnlyWhatIsExpired() {
        String actor = "purge-" + UUID.randomUUID();
        Instant old = Instant.parse("2001-01-01T00:00:00Z");
        Instant recent = Instant.parse("2031-01-01T00:00:00Z");
        sink.record(AuditEvent.builder().category(AuditCategories.INGEST).action("ingest.completed")
                .actor(Actor.of(actor)).occurredAt(old).build());
        sink.record(AuditEvent.builder().category(AuditCategories.INGEST).action("ingest.completed")
                .actor(Actor.of(actor)).occurredAt(recent).build());
        sink.record(AuditEvent.builder().category(AuditCategories.EXPORT).action("run.succeeded")
                .actor(Actor.of(actor)).occurredAt(old).build());

        Instant cutoff = Instant.parse("2002-01-01T00:00:00Z");
        assertThat(events.countOlderThan(AuditCategories.INGEST, cutoff)).isEqualTo(1);
        assertThat(events.purgeOlderThan(AuditCategories.INGEST, cutoff, 100)).isEqualTo(1);

        List<String> left = events.find(AuditTrailQuery.builder().actorSubject(actor).build())
                .stream().map(AuditEventEntity::getCategory).sorted().toList();
        assertThat(left).containsExactly("export", "ingest");
    }

    /**
     * The retention hole this closes: {@code AuditEvent.category} is open, so a service audits its own
     * domain under a category this module has never heard of. A purge that walked the platform's own nine
     * categories would silently never expire those rows - and only from the moment a deployment configured
     * its first per-category override, which is exactly when nobody would be looking.
     */
    @Test
    @DisplayName("a category the platform has never heard of is still purged under the default period")
    void purgesACategoryThePlatformDoesNotKnow() {
        String actor = "unknown-category-" + UUID.randomUUID();
        Instant old = Instant.parse("2004-01-01T00:00:00Z");
        sink.record(AuditEvent.builder().category("order-lifecycle").action("order.cancelled")
                .actor(Actor.of(actor)).occurredAt(old).build());
        sink.record(AuditEvent.builder().category(AuditCategories.ACCESS).action("access.denied")
                .actor(Actor.of(actor)).occurredAt(old).build());

        Instant cutoff = Instant.parse("2005-01-01T00:00:00Z");
        // `access` stands for a category with a retention period of its own: it must survive the pass that
        // expires everything else. Deliberately not `settings`, which the migration fixture occupies -
        // tests that share a category share their assertions.
        Set<String> overridden = Set.of(AuditCategories.ACCESS);
        assertThat(events.countOlderThanExcluding(overridden, cutoff)).isEqualTo(1);
        assertThat(events.purgeOlderThanExcluding(overridden, cutoff, 100)).isEqualTo(1);

        assertThat(events.find(AuditTrailQuery.builder().actorSubject(actor).build()))
                .singleElement()
                .satisfies(left -> assertThat(left.getCategory()).isEqualTo(AuditCategories.ACCESS));
    }

    @Test
    @DisplayName("a purge is bounded by its batch size, so a backlog is worked through rather than loaded")
    void purgesInBatches() {
        String actor = "batch-" + UUID.randomUUID();
        Instant old = Instant.parse("2002-06-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            sink.record(AuditEvent.builder().category(AuditCategories.CONFIG).action("config.reloaded")
                    .actor(Actor.of(actor)).occurredAt(old.plus(i, ChronoUnit.MINUTES)).build());
        }

        Instant cutoff = Instant.parse("2003-01-01T00:00:00Z");
        assertThat(events.purgeOlderThan(AuditCategories.CONFIG, cutoff, 2)).isEqualTo(2);
        assertThat(events.purgeOlderThan(AuditCategories.CONFIG, cutoff, 2)).isEqualTo(2);
        assertThat(events.purgeOlderThan(AuditCategories.CONFIG, cutoff, 2)).isEqualTo(1);
        assertThat(events.purgeOlderThan(AuditCategories.CONFIG, cutoff, 2)).isZero();
    }

    private static AuditEventEntity byResourceId(List<AuditEventEntity> events, String resourceId) {
        return events.stream()
                .filter(event -> resourceId.equals(event.getResourceId()))
                .findFirst().orElseThrow();
    }
}
