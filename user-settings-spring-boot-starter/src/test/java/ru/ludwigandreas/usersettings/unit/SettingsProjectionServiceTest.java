package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.cache.NoopSettingsCache;
import ru.ludwigandreas.usersettings.api.ConsentDecision;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;
import ru.ludwigandreas.usersettings.metrics.NoopSettingsMetrics;
import ru.ludwigandreas.usersettings.projection.SettingsProjectionService;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Order tolerance and idempotence, which are what make a projection safe against an at-least-once,
 * partially-ordered stream.
 *
 * <p>Asserted against the repository rather than against a log line: "did it write" and "what did it
 * write" are the behaviour, and a test that matched on log output would keep passing after the write
 * was removed.
 */
class SettingsProjectionServiceTest {

    private static final String TENANT = "acme";
    private static final String SUBJECT = "user-1";
    private static final Instant NOON = Instant.parse("2026-01-02T12:00:00Z");

    private final UserSettingValueRepository values = mock(UserSettingValueRepository.class);
    private final UserConsentRepository consents = mock(UserConsentRepository.class);

    private final SettingsProjectionService projection = new SettingsProjectionService(
            values, consents, new NoopSettingsCache(), new NoopSettingsMetrics(), "upstream");

    private static UserSettingChangedEvent change(String value, Instant occurredAt) {
        return new UserSettingChangedEvent("evt-1", TENANT, SettingLayer.USER, SUBJECT,
                "user.timezone", value, "zone-id", false, occurredAt);
    }

    private static UserSettingChangedEvent removal(Instant occurredAt) {
        return new UserSettingChangedEvent("evt-2", TENANT, SettingLayer.USER, SUBJECT,
                "user.timezone", null, null, true, occurredAt);
    }

    private static UserSettingValueEntity stored(String value, Instant changedAt) {
        UserSettingValueEntity entity = new UserSettingValueEntity();
        entity.setTenantId(TENANT);
        entity.setScopeType(SettingLayer.USER);
        entity.setScopeId(SUBJECT);
        entity.setSettingKey("user.timezone");
        entity.setValueText(value);
        entity.setValueType("zone-id");
        entity.setChangedAt(changedAt);
        return entity;
    }

    private UserSettingValueEntity capturedSave() {
        ArgumentCaptor<UserSettingValueEntity> saved = ArgumentCaptor.forClass(UserSettingValueEntity.class);
        verify(values).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("a first event for a row is applied")
    void first_event_is_applied() {
        when(values.findValue(TENANT, SettingScope.user(SUBJECT), "user.timezone"))
                .thenReturn(Optional.empty());

        projection.apply(change("Europe/Moscow", NOON));

        UserSettingValueEntity saved = capturedSave();
        assertThat(saved.getValueText()).isEqualTo("Europe/Moscow");
        assertThat(saved.getChangedAt()).isEqualTo(NOON);
        assertThat(saved.isRemoved()).isFalse();
    }

    @Test
    @DisplayName("an event older than what is stored is dropped")
    void out_of_order_event_is_dropped() {
        // Normal rather than exceptional: Kafka orders only within a partition, and a subject's events
        // move between partitions when the topic is scaled.
        when(values.findValue(TENANT, SettingScope.user(SUBJECT), "user.timezone"))
                .thenReturn(Optional.of(stored("Europe/Moscow", NOON)));

        projection.apply(change("Asia/Tokyo", NOON.minusSeconds(60)));

        verify(values, never()).save(any());
    }

    @Test
    @DisplayName("a redelivery of the event already applied is dropped")
    void replayed_event_is_dropped() {
        when(values.findValue(TENANT, SettingScope.user(SUBJECT), "user.timezone"))
                .thenReturn(Optional.of(stored("Europe/Moscow", NOON)));

        projection.apply(change("Europe/Moscow", NOON));

        verify(values, never()).save(any());
    }

    @Test
    @DisplayName("a newer event overwrites what is stored")
    void newer_event_is_applied() {
        when(values.findValue(TENANT, SettingScope.user(SUBJECT), "user.timezone"))
                .thenReturn(Optional.of(stored("Europe/Moscow", NOON)));

        projection.apply(change("Asia/Tokyo", NOON.plusSeconds(60)));

        assertThat(capturedSave().getValueText()).isEqualTo("Asia/Tokyo");
    }

    @Test
    @DisplayName("a removal is stored as a tombstone, not deleted")
    void removal_is_stored_as_a_tombstone() {
        when(values.findValue(TENANT, SettingScope.user(SUBJECT), "user.timezone"))
                .thenReturn(Optional.of(stored("Europe/Moscow", NOON)));

        projection.apply(removal(NOON.plusSeconds(60)));

        UserSettingValueEntity saved = capturedSave();
        assertThat(saved.isRemoved()).isTrue();
        assertThat(saved.getValueText()).isNull();
        assertThat(saved.getValueType()).isNull();
        assertThat(saved.getChangedAt()).isEqualTo(NOON.plusSeconds(60));
    }

    @Test
    @DisplayName("a set that arrives after a removal it predates does not resurrect the value")
    void late_set_does_not_resurrect_a_removed_value() {
        // The reason removals are tombstones. With the row deleted there would be nothing to compare
        // the late event against, and the value would come back from the dead.
        UserSettingValueEntity tombstone = stored(null, NOON.plusSeconds(60));
        tombstone.setRemoved(true);
        tombstone.setValueType(null);
        when(values.findValue(TENANT, SettingScope.user(SUBJECT), "user.timezone"))
                .thenReturn(Optional.of(tombstone));

        projection.apply(change("Europe/Moscow", NOON));

        verify(values, never()).save(any());
    }

    @Test
    @DisplayName("an event with no timestamp is dropped rather than applied blindly")
    void event_without_a_timestamp_is_dropped() {
        // Applying it could silently revert a newer value, because there would be no way to tell it
        // from a replay. Dropping it loses one change; applying it could lose every later one.
        projection.apply(change("Europe/Moscow", null));

        verify(values, never()).save(any());
    }

    @Test
    @DisplayName("a non-removal event carrying no value is discarded")
    void malformed_event_is_discarded() {
        projection.apply(new UserSettingChangedEvent("evt-3", TENANT, SettingLayer.USER, SUBJECT,
                "user.timezone", null, null, false, NOON));

        verify(values, never()).save(any());
    }

    @Test
    @DisplayName("a consent is inserted under the owner's own id")
    void consent_is_inserted_with_the_owners_id() {
        UUID consentId = UUID.randomUUID();
        when(consents.existsById(consentId)).thenReturn(false);

        projection.apply(consent(consentId));

        ArgumentCaptor<UserConsentEntity> saved = ArgumentCaptor.forClass(UserConsentEntity.class);
        verify(consents).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(consentId);
        assertThat(saved.getValue().getSourceSystem()).isEqualTo("upstream");
        assertThat(saved.getValue().getTextVersion()).isEqualTo("v3");
    }

    @Test
    @DisplayName("a redelivered consent is not recorded twice")
    void replayed_consent_is_not_duplicated() {
        UUID consentId = UUID.randomUUID();
        when(consents.existsById(consentId)).thenReturn(true);

        projection.apply(consent(consentId));

        verify(consents, never()).save(any());
    }

    private static ConsentChangedEvent consent(UUID consentId) {
        return new ConsentChangedEvent("evt-c", consentId, TENANT, SUBJECT, "marketing.email", "v3",
                ConsentDecision.GRANTED, "en", NOON, SUBJECT, "10.0.0.1", "agent", "corr-1");
    }
}
