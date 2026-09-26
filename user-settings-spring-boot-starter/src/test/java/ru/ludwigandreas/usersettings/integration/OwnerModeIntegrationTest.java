package ru.ludwigandreas.usersettings.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingUpdate;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;
import ru.ludwigandreas.audit.store.repository.AuditTrailQuery;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillRequest;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillResult;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillService;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.consent.ConsentGrant;
import ru.ludwigandreas.usersettings.consent.ConsentRecord;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.consent.ConsentState;
import ru.ludwigandreas.usersettings.audit.SettingAuditAction;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;
import ru.ludwigandreas.usersettings.exception.SettingNotEditableException;
import ru.ludwigandreas.usersettings.exception.SettingValidationException;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Owner mode against a real PostgreSQL, with the shipped Liquibase migrations and Hibernate
 * validating its mappings against them.
 *
 * <p>Everything asserted here is behaviour a service depends on and that a unit test cannot reach:
 * the unique constraint, the append-only trigger, the actual number of statements a lookup issues,
 * and the transaction boundary the cache eviction hangs off.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(UserSettingsTestConfiguration.class)
@TestPropertySource(properties = {
        "ludwig.user-settings.owner.enabled=true",
        "ludwig.user-settings.default-tenant=acme",
        // No broker in this class: the outbox row is what owner mode is responsible for producing,
        // and delivering it is the outbox module's own tested behaviour. The route still has to be
        // configured, because publishing resolves one at write time and refuses without it - which is
        // itself worth exercising, since a service that enables event publication and forgets the
        // route should find out on its first write rather than at dispatch.
        "ludwig.outbox.polling.enabled=false",
        "ludwig.outbox.default-route.transport=KAFKA",
        "ludwig.outbox.default-route.destination=user.settings",
        "ludwig.security.enabled=false",
        "ludwig.identity.enabled=false",
        // Statement counting is how "getAll issues a single query" is asserted rather than assumed.
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // Stands in for the consuming application's own changelog; see the file for why it exists at
        // all rather than switching Boot's Liquibase off.
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class OwnerModeIntegrationTest {

    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";
    private static final String SUBJECT = "user-1";

    /**
     * Pinned by name, version <em>and</em> digest.
     *
     * <p>A tag alone can be re-pointed at different content, so a test would silently change what it
     * executes. Company policy is name plus version plus digest, everywhere, and a test container is
     * not an exception to it.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:"
                    + "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private SettingsLookup lookup;

    @Autowired
    private SettingsWriter writer;

    @Autowired
    private ConsentService consents;

    @Autowired
    private SettingsCache cache;

    @Autowired
    private UserSettingValueRepository values;

    @Autowired
    private AuditEventRepository audit;


    @Autowired
    private UserConsentRepository consentRows;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private SettingsBackfillService backfill;

    @Autowired
    private OutboxMessageRepository outbox;

    private static SettingsSubject subjectIn(String tenant) {
        return new SettingsSubject(PrincipalRef.user(SUBJECT), tenant);
    }

    @BeforeEach
    void reset() {
        values.deleteAll();
        audit.deleteAll();
        consentRows.deleteAll();
        outbox.deleteAll();
        cache.evictAll();
        UserSettingsTestConfiguration.ROLES.clear();
    }

    @Test
    @DisplayName("a value written by its subject resolves from the user layer")
    void written_value_resolves_from_the_user_layer() {
        ResolvedValue<ZoneId> written =
                writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));

        assertThat(written.value()).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(written.layer()).isEqualTo(SettingLayer.USER);
        assertThat(lookup.get(subjectIn(ACME), IntegrationSettings.TIMEZONE))
                .isEqualTo(ZoneId.of("Europe/Moscow"));
    }

    @Test
    @DisplayName("a tenant value is inherited, and a user value overrides it")
    void tenant_value_is_inherited_until_the_user_overrides_it() {
        writer.setForScope(subjectIn(ACME), SettingScope.tenant(ACME),
                IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Paris"));

        ResolvedValue<ZoneId> inherited = lookup.getAll(subjectIn(ACME)).resolved(IntegrationSettings.TIMEZONE);
        assertThat(inherited.value()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(inherited.layer()).isEqualTo(SettingLayer.TENANT);
        assertThat(inherited.scopeId()).isEqualTo(ACME);

        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));

        ResolvedValue<ZoneId> overridden = lookup.getAll(subjectIn(ACME)).resolved(IntegrationSettings.TIMEZONE);
        assertThat(overridden.value()).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(overridden.layer()).isEqualTo(SettingLayer.USER);
    }

    @Test
    @DisplayName("a lookup never crosses a tenant boundary")
    void lookup_never_crosses_a_tenant_boundary() {
        // The same subject id in two tenants. If the query were unscoped, or the cache keyed on the
        // subject alone, the second read would return the first tenant's value.
        writer.setForScope(subjectIn(ACME), SettingScope.user(SUBJECT),
                IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));

        assertThat(lookup.get(subjectIn(ACME), IntegrationSettings.TIMEZONE))
                .isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(lookup.get(subjectIn(GLOBEX), IntegrationSettings.TIMEZONE))
                .isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("a tenant's stored rows are invisible to another tenant's scope query")
    void stored_rows_are_scoped_to_their_tenant() {
        writer.setForScope(subjectIn(ACME), SettingScope.tenant(ACME),
                IntegrationSettings.PAGE_SIZE, 10);

        assertThat(values.findByScope(ACME, SettingScope.tenant(ACME))).hasSize(1);
        assertThat(values.findByScope(GLOBEX, SettingScope.tenant(ACME))).isEmpty();
        // A tenant scope whose id belongs to another tenant is not a back door either.
        assertThat(values.loadForScopes(GLOBEX, List.of(SettingScope.tenant(ACME)))).isEmpty();
    }

    @Test
    @DisplayName("getAll resolves every setting in one statement")
    void get_all_issues_a_single_query() {
        // The module's defining performance property. Four roles and a tenant value, so the naive
        // layer-at-a-time implementation would issue six statements rather than one.
        UserSettingsTestConfiguration.ROLES.put(SUBJECT,
                List.of("ROLE_A", "ROLE_B", "ROLE_C", "ROLE_D"));
        writer.setForScope(subjectIn(ACME), SettingScope.tenant(ACME),
                IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Paris"));
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);
        cache.evictAll();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        ResolvedSettings resolved = lookup.getAll(subjectIn(ACME));

        assertThat(resolved.size()).isEqualTo(8);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a second getAll is served from the cache without touching the database")
    void repeated_get_all_is_served_from_the_cache() {
        lookup.getAll(subjectIn(ACME));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        lookup.getAll(subjectIn(ACME));

        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("the cache is evicted only after the write commits")
    void cache_is_evicted_after_commit() {
        lookup.getAll(subjectIn(ACME));
        assertThat(cache.get(subjectIn(ACME))).isPresent();

        transactions.executeWithoutResult(status -> {
            writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);
            // Still cached: evicting here would let another thread re-read the pre-change row and
            // repopulate the cache with a value this transaction is about to replace.
            assertThat(cache.get(subjectIn(ACME))).isPresent();
        });

        assertThat(cache.get(subjectIn(ACME))).isEmpty();
        assertThat(lookup.get(subjectIn(ACME), IntegrationSettings.PAGE_SIZE)).isEqualTo(50);
    }

    @Test
    @DisplayName("a rolled-back write leaves the cache alone")
    void rolled_back_write_does_not_evict() {
        lookup.getAll(subjectIn(ACME));

        transactions.executeWithoutResult(status -> {
            writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);
            status.setRollbackOnly();
        });

        assertThat(cache.get(subjectIn(ACME))).isPresent();
        assertThat(lookup.get(subjectIn(ACME), IntegrationSettings.PAGE_SIZE)).isEqualTo(25);
    }

    @Test
    @DisplayName("a reset leaves a tombstone and the layer below supplies the value again")
    void reset_falls_back_to_the_layer_below() {
        writer.setForScope(subjectIn(ACME), SettingScope.tenant(ACME),
                IntegrationSettings.PAGE_SIZE, 10);
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);

        writer.reset(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE);

        ResolvedValue<Integer> resolved = lookup.getAll(subjectIn(ACME)).resolved(IntegrationSettings.PAGE_SIZE);
        assertThat(resolved.value()).isEqualTo(10);
        assertThat(resolved.layer()).isEqualTo(SettingLayer.TENANT);
        assertThat(values.findValue(ACME, SettingScope.user(SUBJECT), "ui.page-size"))
                .get()
                .satisfies(row -> assertThat(row.isRemoved()).isTrue());
    }

    @Test
    @DisplayName("setting a value again after a reset revives the same row")
    void setting_after_a_reset_revives_the_row() {
        // Rather than inserting a second row against the unique constraint.
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);
        writer.reset(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE);
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 75);

        assertThat(values.findAll()).hasSize(1);
        assertThat(lookup.get(subjectIn(ACME), IntegrationSettings.PAGE_SIZE)).isEqualTo(75);
    }

    @Test
    @DisplayName("a bulk update applies all of its changes or none")
    void bulk_update_is_all_or_nothing() {
        assertThatThrownBy(() -> writer.setAll(PrincipalRef.user(SUBJECT), List.of(
                SettingUpdate.of(IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow")),
                SettingUpdate.of(IntegrationSettings.PAGE_SIZE, 500))))
                .isInstanceOf(SettingValidationException.class);

        // The valid change in the same request must not have been written.
        assertThat(lookup.get(subjectIn(ACME), IntegrationSettings.TIMEZONE)).isEqualTo(ZoneId.of("UTC"));
        assertThat(values.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a bulk update reports every rejected setting, not just the first")
    void bulk_update_reports_every_rejection() {
        SettingValidationException rejected = (SettingValidationException) org.assertj.core.api.Assertions
                .catchThrowable(() -> writer.setAll(PrincipalRef.user(SUBJECT), List.of(
                        SettingUpdate.of(IntegrationSettings.PAGE_SIZE, 500),
                        SettingUpdate.of(IntegrationSettings.LOCALE, Locale.FRENCH))));

        assertThat(rejected.getRejected()).hasSize(1);
        assertThat(rejected.getProperties()).containsKey("violations");
    }

    @Test
    @DisplayName("a setting that is not user-editable cannot be written by its subject")
    void not_editable_setting_is_refused() {
        assertThatThrownBy(() ->
                writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.BETA_FEATURES, true))
                .isInstanceOf(SettingNotEditableException.class);
    }

    @Test
    @DisplayName("a change is audited with its old and new value")
    void change_is_audited() {
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 75);

        // The consolidated trail: the same two events, now in audit_event alongside every other
        // subsystem's, found by the subject they were about rather than by this module's own table.
        List<AuditEventEntity> trail = trailFor(SUBJECT);
        assertThat(trail).hasSize(2);
        AuditEventEntity latest = trail.stream()
                .filter(event -> "75".equals(event.getAttributes().get("newValue")))
                .findFirst().orElseThrow();
        assertThat(latest.getAction()).isEqualTo(SettingAuditAction.SET.action());
        assertThat(latest.getResourceId()).isEqualTo("ui.page-size");
        assertThat(latest.getAttributes())
                .containsEntry("settingKey", "ui.page-size")
                .containsEntry("oldValue", "50")
                .containsEntry("newValue", "75")
                .containsEntry("redacted", false);
    }

    @Test
    @DisplayName("a PII-flagged value is redacted in the audit trail")
    void pii_value_is_redacted_in_the_audit_trail() {
        // The trail outlives the data it audits and is read by more people, so a personal value
        // written here would survive every erasure request meant to remove it.
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.CONTACT_NOTE, "+7 900 000 00 00");

        AuditEventEntity entry = trailFor(SUBJECT).get(0);
        assertThat(entry.getAttributes()).containsEntry("redacted", true);
        assertThat(entry.getAttributes()).containsEntry("newValue", Redaction.MASK);
        assertThat((String) entry.getAttributes().get("newValue")).doesNotContain("900");

        // The value itself is still stored and still readable by its owner - redaction governs the
        // trail, not the setting.
        assertThat(lookup.get(subjectIn(ACME), IntegrationSettings.CONTACT_NOTE))
                .isEqualTo("+7 900 000 00 00");
    }

    @Test
    @DisplayName("a consent decision is recorded with the version of the text it was made against")
    void consent_records_the_text_version() {
        ConsentRecord granted = consents.grant(PrincipalRef.user(SUBJECT), ConsentGrant.builder()
                .consentKey("marketing.email")
                .textVersion("v3")
                .locale("en")
                .evidenceIp("10.0.0.1")
                .evidenceUserAgent("test-agent")
                .build());

        assertThat(granted.textVersion()).isEqualTo("v3");

        ConsentState state = consents.currentState(subjectIn(ACME));
        assertThat(state.isGranted("marketing.email")).isTrue();
        assertThat(state.isGrantedForVersion("marketing.email", "v3")).isTrue();
        // A grant against v3 does not answer for v4, which is the whole reason the version is stored.
        assertThat(state.isGrantedForVersion("marketing.email", "v4")).isFalse();
    }

    @Test
    @DisplayName("revocation is a new row and the grant is left untouched")
    void revocation_appends_rather_than_mutating() {
        consents.grant(PrincipalRef.user(SUBJECT), grantOf("v3"));
        consents.revoke(PrincipalRef.user(SUBJECT), grantOf("v3"));

        assertThat(consents.history(subjectIn(ACME), "marketing.email")).hasSize(2);
        assertThat(consents.currentState(subjectIn(ACME)).isGranted("marketing.email")).isFalse();
    }

    @Test
    @DisplayName("a consent row cannot be updated")
    void consent_rows_are_immutable() {
        // Enforced by db-core's SnapshotImmutabilityListener, with a database trigger behind it -
        // append-only is a claim made to auditors, and one enforced only by convention is not worth
        // making.
        consents.grant(PrincipalRef.user(SUBJECT), grantOf("v3"));
        UserConsentEntity stored = consentRows.findAll().get(0);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UserConsentEntity managed = consentRows.findById(stored.getId()).orElseThrow();
            managed.setDecision(ru.ludwigandreas.usersettings.api.ConsentDecision.REVOKED);
            consentRows.saveAndFlush(managed);
        })).isInstanceOf(IntegrityViolationException.class);
    }

    @Test
    @DisplayName("consent state can be reconstructed as it stood at a past instant")
    void consent_state_can_be_reconstructed_for_a_past_instant() {
        ConsentRecord granted = consents.grant(PrincipalRef.user(SUBJECT), grantOf("v3"));
        consents.revoke(PrincipalRef.user(SUBJECT), ConsentGrant.builder()
                .consentKey("marketing.email")
                .textVersion("v3")
                .occurredAt(granted.occurredAt().plusSeconds(3600))
                .build());

        ConsentState now = consents.currentState(subjectIn(ACME));
        ConsentState before = consents.stateAsOf(subjectIn(ACME), granted.occurredAt().plusSeconds(60));

        assertThat(now.isGranted("marketing.email")).isFalse();
        assertThat(before.isGranted("marketing.email")).isTrue();
    }

    @Test
    @DisplayName("an unknown tenant's settings resolve to defaults rather than to somebody else's")
    void unknown_tenant_resolves_to_defaults() {
        writer.setForScope(subjectIn(ACME), SettingScope.tenant(ACME),
                IntegrationSettings.PAGE_SIZE, 10);

        Optional<ResolvedValue<Integer>> resolved = Optional.of(
                lookup.getAll(new SettingsSubject(PrincipalRef.user("someone-else"), "unknown-tenant"))
                        .resolved(IntegrationSettings.PAGE_SIZE));

        assertThat(resolved).get().satisfies(value -> {
            assertThat(value.value()).isEqualTo(25);
            assertThat(value.layer()).isEqualTo(SettingLayer.DEFAULT);
        });
    }

    @Test
    @DisplayName("a tenant-scoped write naming another tenant is refused")
    void tenant_scoped_write_cannot_name_another_tenant() {
        // Found by the self-revalidation pass. The row would have been filed under the caller's tenant
        // with another tenant's id as its scope - unreadable by any resolution and invisible on any
        // screen, so the write would have looked like it worked and done nothing.
        assertThatThrownBy(() -> writer.setForScope(subjectIn(ACME), SettingScope.tenant(GLOBEX),
                IntegrationSettings.PAGE_SIZE, 10))
                .isInstanceOf(ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException.class);

        assertThat(values.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an administrator reading another subject's settings is audited")
    void administrative_read_is_audited() {
        // The row looks redundant until the first time somebody asks who looked at a user's data, at
        // which point it is the only record that could answer.
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);
        audit.deleteAll();

        authenticateAsAdmin();
        try {
            lookup.getAll(subjectIn(ACME));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        List<AuditEventEntity> trail = trailFor(SUBJECT);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).getAction()).isEqualTo(SettingAuditAction.ADMIN_READ.action());
        // actor is who looked, onBehalfOf is whose settings they looked at - the direction the old
        // table's `actor`/`subject` columns meant, now named for it.
        assertThat(trail.get(0).getActorSubject()).isEqualTo("admin-1");
        assertThat(trail.get(0).getOnBehalfOf()).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("a subject reading their own settings is not audited")
    void self_read_is_not_audited() {
        // A row per self-service page view would bury the administrative reads that matter.
        authenticateAsSubject();
        try {
            lookup.getAll(subjectIn(ACME));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        assertThat(trailFor(SUBJECT)).isEmpty();
    }

    /** The settings trail for one subject, from the platform's consolidated table. */
    private List<AuditEventEntity> trailFor(String subject) {
        return audit.find(AuditTrailQuery.builder()
                .categories(List.of("settings"))
                .onBehalfOf(subject)
                .limit(10)
                .build());
    }

    private static void authenticateAsAdmin() {
        authenticate("admin-1", "ROLE_SETTINGS_ADMIN");
    }

    private static void authenticateAsSubject() {
        authenticate(SUBJECT);
    }

    private static void authenticate(String subject, String... roles) {
        ru.ludwigandreas.security.principal.LudwigPrincipal principal =
                ru.ludwigandreas.security.principal.LudwigPrincipal.builder()
                        .subject(subject)
                        .type(ru.ludwigandreas.security.principal.PrincipalType.USER)
                        .tenantId(ACME)
                        .roles(List.of(roles))
                        .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, "n/a", List.of()));
    }

    // ---------------------------------------------------------------------------------------------
    // Backfill: republishing stored state so a new or rebuilt projection can be seeded.
    //
    // These run against the real query rather than a mock because that is where the two ways to get
    // this wrong live: a keyset page that silently skips rows, and a filter that excludes tombstones
    // and so leaves a reset value resurrected in every replica.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a backfill republishes stored values carrying the timestamp they were first written with")
    void backfill_republishes_stored_values_with_their_original_timestamp() {
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
        Instant writtenAt = values.findAll().get(0).getChangedAt();
        outbox.deleteAll();

        SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.forTenant(ACME));

        assertThat(result.settingRows()).isEqualTo(1);
        assertThat(result.complete()).isTrue();

        OutboxMessage message = onlySettingMessage();
        JsonNode payload = payloadOf(message);
        assertThat(payload.get("settingKey").asText()).isEqualTo("user.timezone");
        assertThat(payload.get("valueText").asText()).isEqualTo("Europe/Moscow");
        // The property the whole feature depends on: not "now", so a replica that is already current
        // recognizes this as stale and keeps what it has.
        assertThat(Instant.parse(payload.get("occurredAt").asText())).isEqualTo(writtenAt);
        // Keyed by subject, which is what keeps one user's changes in order behind one partition.
        assertThat(message.getOrderingKey()).isEqualTo(ACME + "/USER/" + SUBJECT);
    }

    @Test
    @DisplayName("a backfill republishes tombstones, so a reset is not undone by seeding a replica")
    void backfill_republishes_tombstones() {
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
        writer.reset(PrincipalRef.user(SUBJECT), IntegrationSettings.TIMEZONE);
        outbox.deleteAll();

        assertThat(backfill.backfill(SettingsBackfillRequest.forTenant(ACME)).settingRows()).isEqualTo(1);

        assertThat(payloadOf(onlySettingMessage()).get("removed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a backfill scoped to one tenant never republishes another tenant's rows")
    void backfill_does_not_cross_a_tenant_boundary() {
        writer.setForScope(subjectIn(ACME), SettingScope.tenant(ACME),
                IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Paris"));
        writer.setForScope(subjectIn(GLOBEX), SettingScope.tenant(GLOBEX),
                IntegrationSettings.TIMEZONE, ZoneId.of("America/New_York"));
        outbox.deleteAll();

        SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.forTenant(ACME));

        assertThat(result.settingRows()).isEqualTo(1);
        assertThat(payloadOf(onlySettingMessage()).get("tenantId").asText()).isEqualTo(ACME);
    }

    @Test
    @DisplayName("an estate-wide backfill covers every tenant - the one deliberately unscoped operation")
    void an_estate_wide_backfill_covers_every_tenant() {
        writer.setForScope(subjectIn(ACME), SettingScope.tenant(ACME),
                IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Paris"));
        writer.setForScope(subjectIn(GLOBEX), SettingScope.tenant(GLOBEX),
                IntegrationSettings.TIMEZONE, ZoneId.of("America/New_York"));
        outbox.deleteAll();

        SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.builder().build());

        assertThat(result.settingRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("narrowing to one setting key republishes only that key - the new-consumer case")
    void backfill_can_be_narrowed_to_one_setting() {
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.PAGE_SIZE, 50);
        outbox.deleteAll();

        SettingsBackfillResult result =
                backfill.backfill(SettingsBackfillRequest.forSettings(ACME, "user.timezone"));

        assertThat(result.settingRows()).isEqualTo(1);
        assertThat(result.consentRows()).isZero();
        assertThat(payloadOf(onlySettingMessage()).get("settingKey").asText()).isEqualTo("user.timezone");
    }

    @Test
    @DisplayName("the keyset scan pages through more rows than one batch holds, without skipping any")
    void backfill_pages_through_every_row() {
        for (int i = 0; i < 7; i++) {
            writer.setForScope(subjectIn(ACME), SettingScope.user("user-" + i),
                    IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
        }
        outbox.deleteAll();

        SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.builder()
                .tenantId(ACME).batchSize(2).includeConsents(false).build());

        // Seven rows in pages of two: four full batches and a short one that ends the scan.
        assertThat(result.settingRows()).isEqualTo(7);
        assertThat(result.batches()).isEqualTo(4);
        assertThat(settingMessages()).hasSize(7);
    }

    @Test
    @DisplayName("the consent ledger is republished without reusing the original idempotency key")
    void backfill_republishes_consents_without_the_original_idempotency_key() {
        consents.grant(PrincipalRef.user(SUBJECT), grantOf("2026-01-01"));
        // Left in place rather than cleared: the original publication's idempotency key is exactly
        // what would swallow the replay if the backfill reused it.
        long before = outbox.count();

        SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.builder()
                .tenantId(ACME).includeSettings(false).build());

        assertThat(result.consentRows()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(before + 1);
        OutboxMessage replay = outbox.findAll().stream()
                .filter(message -> message.getIdempotencyKey() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the replay was swallowed as a duplicate"));
        assertThat(payloadOf(replay).get("consentKey").asText()).isEqualTo("marketing.email");
        assertThat(payloadOf(replay).get("textVersion").asText()).isEqualTo("2026-01-01");
    }

    @Test
    @DisplayName("running a backfill twice publishes the same events again, which is what makes it re-runnable")
    void a_backfill_is_safe_to_run_twice() {
        writer.set(PrincipalRef.user(SUBJECT), IntegrationSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
        outbox.deleteAll();

        backfill.backfill(SettingsBackfillRequest.forSettings(ACME, "user.timezone"));
        backfill.backfill(SettingsBackfillRequest.forSettings(ACME, "user.timezone"));

        List<OutboxMessage> messages = settingMessages();
        assertThat(messages).hasSize(2);
        // Identical payloads, including occurredAt - which is why the second delivery is dropped by
        // the projection rather than applied twice. Recovery from a half-finished run is "run it again".
        assertThat(payloadOf(messages.get(0)).get("occurredAt"))
                .isEqualTo(payloadOf(messages.get(1)).get("occurredAt"));
    }

    private List<OutboxMessage> settingMessages() {
        return outbox.findAll().stream()
                .filter(message -> "UserSettingChanged".equals(message.getEventType()))
                .toList();
    }

    private OutboxMessage onlySettingMessage() {
        List<OutboxMessage> messages = settingMessages();
        assertThat(messages).hasSize(1);
        return messages.get(0);
    }

    private static JsonNode payloadOf(OutboxMessage message) {
        try {
            return new ObjectMapper().readTree(message.getPayload());
        } catch (Exception e) {
            throw new AssertionError("outbox payload was not readable JSON", e);
        }
    }

    private static ConsentGrant grantOf(String textVersion) {
        return ConsentGrant.builder()
                .consentKey("marketing.email")
                .textVersion(textVersion)
                .locale("en")
                .build();
    }
}
