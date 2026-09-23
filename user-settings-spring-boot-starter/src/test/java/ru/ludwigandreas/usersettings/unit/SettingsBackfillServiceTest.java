package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillBatch;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillBatchPublisher;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillRequest;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillResult;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillService;
import ru.ludwigandreas.usersettings.event.NoopSettingsEventPublisher;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;
import ru.ludwigandreas.usersettings.metrics.NoopSettingsMetrics;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;

/**
 * The paging loop, the row budget, and the refusal to run when nothing is listening.
 *
 * <p>The batch publisher is mocked here rather than exercised, because what this class is responsible
 * for is entirely about how many times it calls that publisher and with which cursor - and a test
 * that went through a real repository would assert that far less directly.
 */
class SettingsBackfillServiceTest {

    private static final String TENANT = "acme";

    private final SettingsBackfillBatchPublisher batches = mock(SettingsBackfillBatchPublisher.class);
    private final SettingsEventPublisher events = mock(SettingsEventPublisher.class);
    private final SettingsMetrics metrics = mock(SettingsMetrics.class);

    private final SettingsBackfillService service =
            new SettingsBackfillService(batches, events, metrics);

    private static SettingsBackfillBatch page(int rows) {
        return new SettingsBackfillBatch(rows, rows == 0 ? null : UUID.randomUUID());
    }

    private void publishing() {
        when(events.publishes()).thenReturn(true);
        when(batches.publishConsents(any(), any())).thenReturn(SettingsBackfillBatch.EMPTY);
        when(batches.publishSettings(any(), any())).thenReturn(SettingsBackfillBatch.EMPTY);
    }

    @Test
    @DisplayName("refuses to run when no publisher is active, instead of reporting a successful no-op")
    void refuses_when_nothing_would_be_published() {
        SettingsBackfillService noop = new SettingsBackfillService(
                batches, new NoopSettingsEventPublisher(), new NoopSettingsMetrics());

        assertThatThrownBy(() -> noop.backfill(SettingsBackfillRequest.forTenant(TENANT)))
                .isInstanceOf(SettingConfigurationException.class)
                .hasMessageContaining("publish-events");

        // The failure mode this guards: an operator watching a backfill report thousands of rows
        // while the replica it was meant to seed stays empty.
        verifyNoInteractions(batches);
    }

    @Test
    @DisplayName("pages until a short batch, resuming each page from the previous page's last id")
    void pages_until_a_short_batch() {
        publishing();
        SettingsBackfillBatch first = page(2);
        SettingsBackfillBatch second = page(2);
        when(batches.publishSettings(any(), isNull())).thenReturn(first);
        when(batches.publishSettings(any(), eq(first.lastId()))).thenReturn(second);
        when(batches.publishSettings(any(), eq(second.lastId()))).thenReturn(page(1));

        SettingsBackfillRequest request = SettingsBackfillRequest.builder()
                .tenantId(TENANT).batchSize(2).includeConsents(false).build();
        SettingsBackfillResult result = service.backfill(request);

        assertThat(result.settingRows()).isEqualTo(5);
        assertThat(result.batches()).isEqualTo(3);
        assertThat(result.complete()).isTrue();
        verify(metrics).recordBackfillPublished("setting", 5L);
    }

    @Test
    @DisplayName("an empty table costs exactly one query")
    void an_empty_table_costs_one_query() {
        publishing();

        SettingsBackfillResult result = service.backfill(SettingsBackfillRequest.builder()
                .tenantId(TENANT).includeConsents(false).build());

        assertThat(result.totalRows()).isZero();
        assertThat(result.batches()).isEqualTo(1);
        assertThat(result.complete()).isTrue();
        verify(batches).publishSettings(any(), isNull());
    }

    @Test
    @DisplayName("a spent row budget stops the run and reports it as incomplete")
    void a_spent_budget_reports_an_incomplete_run() {
        publishing();
        when(batches.publishSettings(any(), any())).thenReturn(page(2));

        SettingsBackfillResult result = service.backfill(SettingsBackfillRequest.builder()
                .tenantId(TENANT).batchSize(2).maxRows(3).build());

        // Three rows asked for, two per batch: the run stops after the batch that crosses the budget,
        // which is what "a budget, not a cap" means.
        assertThat(result.settingRows()).isEqualTo(4);
        assertThat(result.complete()).isFalse();
        // The budget is shared, so the consent scan does not get a fresh allowance.
        assertThat(result.consentRows()).isZero();
        verify(batches, never()).publishConsents(any(), any());
    }

    @Test
    @DisplayName("the two halves can be run separately")
    void settings_and_consents_are_independently_selectable() {
        publishing();
        when(batches.publishConsents(any(), isNull())).thenReturn(page(3));

        SettingsBackfillResult result = service.backfill(SettingsBackfillRequest.builder()
                .tenantId(TENANT).batchSize(10).includeSettings(false).build());

        assertThat(result.settingRows()).isZero();
        assertThat(result.consentRows()).isEqualTo(3);
        verify(batches, never()).publishSettings(any(), any());
        verify(metrics).recordBackfillPublished("consent", 3L);
    }
}
