package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillRequest;

/** The defaults an operator gets without asking, and the requests that are refused outright. */
class SettingsBackfillRequestTest {

    @Test
    @DisplayName("the defaults republish everything, batched")
    void defaults_republish_everything() {
        SettingsBackfillRequest request = SettingsBackfillRequest.forTenant("acme");

        assertThat(request.includeSettings()).isTrue();
        assertThat(request.includeConsents()).isTrue();
        assertThat(request.batchSize()).isEqualTo(SettingsBackfillRequest.DEFAULT_BATCH_SIZE);
        assertThat(request.maxRows()).isZero();
        assertThat(request.settingKeys()).isEmpty();
    }

    @Test
    @DisplayName("a null tenant is allowed here, and means every tenant")
    void a_null_tenant_means_every_tenant() {
        assertThat(SettingsBackfillRequest.builder().build().tenantId()).isNull();
    }

    @Test
    @DisplayName("narrowing to named settings leaves consents out, which is the new-consumer case")
    void narrowing_to_named_settings_excludes_consents() {
        SettingsBackfillRequest request = SettingsBackfillRequest.forSettings("acme", "user.timezone");

        assertThat(request.settingKeys()).containsExactly("user.timezone");
        assertThat(request.includeConsents()).isFalse();
    }

    @Test
    @DisplayName("key sets are defensively copied, so a caller cannot mutate a request mid-run")
    void key_sets_are_copied() {
        Set<String> keys = new java.util.LinkedHashSet<>(Set.of("user.timezone"));
        SettingsBackfillRequest request =
                SettingsBackfillRequest.builder().tenantId("acme").settingKeys(keys).build();
        keys.add("user.locale");

        assertThat(request.settingKeys()).containsExactly("user.timezone");
    }

    @Test
    @DisplayName("a request that would do nothing is refused rather than run")
    void a_request_that_does_nothing_is_refused() {
        assertThatThrownBy(() -> SettingsBackfillRequest.builder()
                .includeSettings(false).includeConsents(false).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither settings nor consents");

        assertThatThrownBy(() -> SettingsBackfillRequest.builder().batchSize(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");

        assertThatThrownBy(() -> SettingsBackfillRequest.builder().maxRows(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRows");
    }
}
