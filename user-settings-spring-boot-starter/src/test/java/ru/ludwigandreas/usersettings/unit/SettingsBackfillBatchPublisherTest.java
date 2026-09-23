package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ludwigandreas.usersettings.api.ConsentDecision;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillBatch;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillRequest;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillBatchPublisher;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * What a republished event actually contains.
 *
 * <p>The assertions here are narrow on purpose. A backfill is only safe because each event carries
 * the owner's original timestamp and a tombstone is republished as a tombstone; get either wrong and
 * the feature turns from "seeds a replica" into "corrupts one", with no symptom at the owner. So
 * those two are asserted field by field rather than through a round trip that could pass for the
 * wrong reason.
 */
class SettingsBackfillBatchPublisherTest {

    private static final String TENANT = "acme";
    private static final Instant LONG_AGO = Instant.parse("2024-03-04T10:15:30Z");

    private final UserSettingValueRepository values = mock(UserSettingValueRepository.class);
    private final UserConsentRepository consents = mock(UserConsentRepository.class);
    private final SettingsEventPublisher events = mock(SettingsEventPublisher.class);

    private final SettingsBackfillBatchPublisher publisher =
            new SettingsBackfillBatchPublisher(values, consents, events);

    private static SettingsBackfillRequest request() {
        return SettingsBackfillRequest.builder().tenantId(TENANT).batchSize(10).build();
    }

    private static UserSettingValueEntity row(String value, Instant changedAt) {
        UserSettingValueEntity entity = new UserSettingValueEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT);
        entity.setScopeType(SettingLayer.USER);
        entity.setScopeId("user-1");
        entity.setSettingKey("user.timezone");
        entity.setValueText(value);
        entity.setValueType("zone-id");
        entity.setChangedAt(changedAt);
        return entity;
    }

    @Test
    @DisplayName("a republished event carries the owner's original timestamp, not the current one")
    void republished_event_carries_the_stored_change_timestamp() {
        when(values.findForBackfill(eq(TENANT), any(), isNull(), eq(10)))
                .thenReturn(List.of(row("Europe/Moscow", LONG_AGO)));

        publisher.publishSettings(request(), null);

        ArgumentCaptor<UserSettingChangedEvent> captor =
                ArgumentCaptor.forClass(UserSettingChangedEvent.class);
        verify(events).publishSettingChanged(captor.capture());
        UserSettingChangedEvent event = captor.getValue();

        // The whole safety argument. With "now" here, a backfill run against a current replica would
        // look newer than everything it holds and would overwrite live values with a stale scan.
        assertThat(event.occurredAt()).isEqualTo(LONG_AGO);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.scopeType()).isEqualTo(SettingLayer.USER);
        assertThat(event.scopeId()).isEqualTo("user-1");
        assertThat(event.settingKey()).isEqualTo("user.timezone");
        assertThat(event.valueText()).isEqualTo("Europe/Moscow");
        assertThat(event.valueType()).isEqualTo("zone-id");
        assertThat(event.removed()).isFalse();
    }

    @Test
    @DisplayName("a tombstone is republished as a removal, so a reset is not undone by the seeding")
    void tombstones_are_republished_as_removals() {
        UserSettingValueEntity tombstone = row(null, LONG_AGO);
        tombstone.setValueType(null);
        tombstone.setRemoved(true);
        when(values.findForBackfill(eq(TENANT), any(), isNull(), eq(10))).thenReturn(List.of(tombstone));

        publisher.publishSettings(request(), null);

        ArgumentCaptor<UserSettingChangedEvent> captor =
                ArgumentCaptor.forClass(UserSettingChangedEvent.class);
        verify(events).publishSettingChanged(captor.capture());
        assertThat(captor.getValue().removed()).isTrue();
        assertThat(captor.getValue().valueText()).isNull();
    }

    @Test
    @DisplayName("the batch reports the last id it read, which is the next page's cursor")
    void batch_reports_the_last_id_as_the_cursor() {
        UserSettingValueEntity first = row("Europe/Moscow", LONG_AGO);
        UserSettingValueEntity last = row("Europe/Paris", LONG_AGO);
        when(values.findForBackfill(eq(TENANT), any(), isNull(), eq(10)))
                .thenReturn(List.of(first, last));

        SettingsBackfillBatch batch = publisher.publishSettings(request(), null);

        assertThat(batch.rowCount()).isEqualTo(2);
        assertThat(batch.lastId()).isEqualTo(last.getId());
    }

    @Test
    @DisplayName("an empty page publishes nothing and reports no cursor")
    void an_empty_page_publishes_nothing() {
        when(values.findForBackfill(eq(TENANT), any(), isNull(), eq(10))).thenReturn(List.of());

        SettingsBackfillBatch batch = publisher.publishSettings(request(), null);

        assertThat(batch.rowCount()).isZero();
        assertThat(batch.lastId()).isNull();
        verify(events, never()).publishSettingChanged(any());
    }

    @Test
    @DisplayName("consents go out through the replay path, so the outbox idempotency key is not reused")
    void consents_are_republished_without_the_idempotency_key() {
        UserConsentEntity consent = new UserConsentEntity();
        consent.setId(UUID.randomUUID());
        consent.setTenantId(TENANT);
        consent.setSubject("user-1");
        consent.setConsentKey("marketing.email");
        consent.setTextVersion("2024-01-01");
        consent.setDecision(ConsentDecision.GRANTED);
        consent.setOccurredAt(LONG_AGO);
        consent.setActor("user-1");
        when(consents.findForBackfill(eq(TENANT), any(), isNull(), eq(10))).thenReturn(List.of(consent));

        publisher.publishConsents(request(), null);

        // The distinction the whole republishConsent method exists for: the normal path would stamp
        // the consent id as an outbox idempotency key, and the original publication's row may still
        // be there to swallow it.
        verify(events, never()).publishConsentChanged(any());
        ArgumentCaptor<ConsentChangedEvent> captor = ArgumentCaptor.forClass(ConsentChangedEvent.class);
        verify(events).republishConsent(captor.capture());
        assertThat(captor.getValue().consentId()).isEqualTo(consent.getId());
        assertThat(captor.getValue().occurredAt()).isEqualTo(LONG_AGO);
        assertThat(captor.getValue().textVersion()).isEqualTo("2024-01-01");
    }
}
