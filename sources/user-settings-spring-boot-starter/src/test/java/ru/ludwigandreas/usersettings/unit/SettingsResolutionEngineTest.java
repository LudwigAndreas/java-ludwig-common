package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.ScopedValue;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingScopeResolver;
import ru.ludwigandreas.usersettings.api.SettingValueSource;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.metrics.NoopSettingsMetrics;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.resolve.ConfiguredSettingValueSource;
import ru.ludwigandreas.usersettings.resolve.PlatformSettingScopeResolver;
import ru.ludwigandreas.usersettings.resolve.SettingDefaultsProvider;
import ru.ludwigandreas.usersettings.resolve.SettingsResolutionEngine;
import ru.ludwigandreas.usersettings.resolve.TenantSettingScopeResolver;
import ru.ludwigandreas.usersettings.resolve.UserSettingScopeResolver;

/**
 * Resolution precedence, which is the module's central behaviour.
 *
 * <p>Asserted on the resolved value <em>and</em> on the layer it came from, because a test that only
 * checked the value would pass for a resolver that returned the right answer by accident - and the
 * layer is half of what callers get back.
 */
class SettingsResolutionEngineTest {

    private static final String TENANT = "acme";
    private static final String SUBJECT = "user-1";
    private static final SettingsSubject SETTINGS_SUBJECT =
            new SettingsSubject(PrincipalRef.user(SUBJECT), TENANT);

    /** A scope resolver for the role layer that returns exactly the roles it was given, in order. */
    private static SettingScopeResolver roles(String... roleCodes) {
        List<SettingScope> scopes = new ArrayList<>();
        for (String role : roleCodes) {
            scopes.add(SettingScope.role(role));
        }
        return new SettingScopeResolver() {
            @Override
            public SettingLayer layer() {
                return SettingLayer.ROLE;
            }

            @Override
            public List<SettingScope> scopesFor(SettingsSubject subject) {
                return scopes;
            }
        };
    }

    private static SettingValueSource source(ScopedValue... values) {
        List<ScopedValue> held = List.of(values);
        return (subject, scopes) -> held.stream().filter(value -> scopes.contains(value.scope())).toList();
    }

    private static ScopedValue zone(SettingScope scope, String zoneId) {
        return new ScopedValue(scope, "user.timezone", zoneId, "zone-id", Instant.EPOCH);
    }

    private static SettingsResolutionEngine engine(List<SettingScopeResolver> resolvers,
                                                   List<SettingValueSource> sources) {
        SettingDefinitionRegistry registry = new SettingDefinitionRegistry(
                List.of(TestSettings.source()),
                new SettingValueConverterRegistry(List.of(), new ObjectMapper()));
        return new SettingsResolutionEngine(registry, resolvers, sources, new NoopSettingsMetrics());
    }

    private static List<SettingScopeResolver> allLayers(SettingScopeResolver roleResolver) {
        return List.of(new UserSettingScopeResolver(), roleResolver,
                new TenantSettingScopeResolver(), new PlatformSettingScopeResolver());
    }

    @Test
    @DisplayName("falls back to the definition default when nothing is stored")
    void default_when_nothing_is_stored() {
        ResolvedSettings resolved =
                engine(allLayers(roles()), List.of(source())).resolve(SETTINGS_SUBJECT);

        ResolvedValue<ZoneId> timezone = resolved.resolved(TestSettings.TIMEZONE);
        assertThat(timezone.value()).isEqualTo(ZoneId.of("UTC"));
        assertThat(timezone.layer()).isEqualTo(SettingLayer.DEFAULT);
        assertThat(timezone.isDefault()).isTrue();
    }

    @Test
    @DisplayName("every declared setting is present, including the ones nobody has set")
    void every_declared_setting_is_present() {
        ResolvedSettings resolved =
                engine(allLayers(roles()), List.of(source())).resolve(SETTINGS_SUBJECT);

        // A caller must never have to distinguish "absent" from "default": that distinction exists in
        // the storage and has no meaning in the API.
        assertThat(resolved.entries()).hasSize(5);
        assertThat(resolved.get(TestSettings.LOCALE)).isEqualTo(Locale.ENGLISH);
        assertThat(resolved.get(TestSettings.CONTACT_NOTE)).isNull();
    }

    @Test
    @DisplayName("user beats role beats tenant beats platform")
    void precedence_runs_most_specific_first() {
        SettingScope user = SettingScope.user(SUBJECT);
        SettingScope role = SettingScope.role("ROLE_STAFF");
        SettingScope tenant = SettingScope.tenant(TENANT);
        SettingScope platform = SettingScope.platform();

        assertThat(resolveWith(roles("ROLE_STAFF"),
                zone(user, "Europe/Moscow"), zone(role, "Europe/Berlin"),
                zone(tenant, "Europe/Paris"), zone(platform, "Asia/Tokyo")))
                .isEqualTo(new ResolvedValue<>(ZoneId.of("Europe/Moscow"), SettingLayer.USER, SUBJECT));

        assertThat(resolveWith(roles("ROLE_STAFF"),
                zone(role, "Europe/Berlin"), zone(tenant, "Europe/Paris"), zone(platform, "Asia/Tokyo")))
                .isEqualTo(new ResolvedValue<>(ZoneId.of("Europe/Berlin"), SettingLayer.ROLE, "ROLE_STAFF"));

        assertThat(resolveWith(roles("ROLE_STAFF"),
                zone(tenant, "Europe/Paris"), zone(platform, "Asia/Tokyo")))
                .isEqualTo(new ResolvedValue<>(ZoneId.of("Europe/Paris"), SettingLayer.TENANT, TENANT));

        assertThat(resolveWith(roles("ROLE_STAFF"), zone(platform, "Asia/Tokyo")))
                .isEqualTo(new ResolvedValue<>(
                        ZoneId.of("Asia/Tokyo"), SettingLayer.PLATFORM, SettingScope.GLOBAL_ID));
    }

    @Test
    @DisplayName("within the role layer, the resolver's own order decides")
    void role_order_is_the_resolvers_order() {
        // Not alphabetical, not "most permissive" - the platform's rule, expressed as the order the
        // scope resolver listed. Asserting both directions is what makes that a rule rather than a
        // coincidence of iteration order.
        SettingScope first = SettingScope.role("ROLE_EDITOR");
        SettingScope second = SettingScope.role("ROLE_STAFF");

        assertThat(resolveWith(roles("ROLE_EDITOR", "ROLE_STAFF"),
                zone(first, "Europe/Berlin"), zone(second, "Europe/Paris")).value())
                .isEqualTo(ZoneId.of("Europe/Berlin"));

        assertThat(resolveWith(roles("ROLE_STAFF", "ROLE_EDITOR"),
                zone(first, "Europe/Berlin"), zone(second, "Europe/Paris")).value())
                .isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    @DisplayName("a value stored under a different encoding is skipped, not coerced")
    void unreadable_encoding_falls_through_to_the_next_layer() {
        // What a setting whose type changed between releases looks like. One bad row must cost that
        // one setting its most specific layer, not fail the whole resolution.
        ScopedValue wrongEncoding =
                new ScopedValue(SettingScope.user(SUBJECT), "user.timezone", "42", "integer", Instant.EPOCH);

        ResolvedValue<ZoneId> resolved = resolveWith(roles(),
                wrongEncoding, zone(SettingScope.tenant(TENANT), "Europe/Paris"));

        assertThat(resolved.value()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(resolved.layer()).isEqualTo(SettingLayer.TENANT);
    }

    @Test
    @DisplayName("a value that will not parse is skipped, not propagated")
    void unparseable_value_falls_through_to_the_next_layer() {
        ScopedValue nonsense = new ScopedValue(
                SettingScope.user(SUBJECT), "user.timezone", "Mars/Olympus", "zone-id", Instant.EPOCH);

        ResolvedValue<ZoneId> resolved = resolveWith(roles(), nonsense);

        assertThat(resolved.value()).isEqualTo(ZoneId.of("UTC"));
        assertThat(resolved.layer()).isEqualTo(SettingLayer.DEFAULT);
    }

    @Test
    @DisplayName("an earlier source wins over a later one at the same scope")
    void earlier_source_wins_at_the_same_scope() {
        // How a stored row beats a configured default without either source knowing the other exists.
        SettingScope tenant = SettingScope.tenant(TENANT);
        SettingValueSource stored = source(zone(tenant, "Europe/Moscow"));
        SettingValueSource configured = source(zone(tenant, "Asia/Tokyo"));

        ResolvedSettings resolved = engine(allLayers(roles()), List.of(stored, configured))
                .resolve(SETTINGS_SUBJECT);

        assertThat(resolved.get(TestSettings.TIMEZONE)).isEqualTo(ZoneId.of("Europe/Moscow"));
    }

    @Test
    @DisplayName("every scope is offered to the sources in one call")
    void sources_are_asked_once_for_every_scope() {
        // The property that makes getAll a single query: the scope set is complete before anything is
        // fetched, so a source has no interface through which an N+1 could happen.
        List<List<SettingScope>> asked = new ArrayList<>();
        SettingValueSource recording = (subject, scopes) -> {
            asked.add(scopes);
            return List.of();
        };

        engine(allLayers(roles("ROLE_A", "ROLE_B")), List.of(recording)).resolve(SETTINGS_SUBJECT);

        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).containsExactly(
                SettingScope.user(SUBJECT),
                SettingScope.role("ROLE_A"),
                SettingScope.role("ROLE_B"),
                SettingScope.tenant(TENANT),
                SettingScope.platform());
    }

    @Test
    @DisplayName("a configured tenant default is only read for the subject's own tenant")
    void configured_tenant_defaults_are_confined_to_the_subjects_tenant() {
        // Found by the self-revalidation pass. The database source is scoped by the subject's tenant
        // and cannot be talked into another one; a custom scope resolver naming a foreign tenant could
        // otherwise have reached that tenant's configured defaults - the same leak one level up.
        SettingDefaultsProvider defaults = new SettingDefaultsProvider() {

            @Override
            public Map<String, String> platformDefaults() {
                return Map.of();
            }

            @Override
            public Map<String, String> tenantDefaults(String tenantId) {
                return Map.of("user.timezone", "globex".equals(tenantId) ? "Asia/Tokyo" : "Europe/Paris");
            }
        };
        SettingDefinitionRegistry registry = new SettingDefinitionRegistry(
                List.of(TestSettings.source()),
                new SettingValueConverterRegistry(List.of(), new ObjectMapper()));
        SettingScopeResolver foreignTenant = new SettingScopeResolver() {

            @Override
            public SettingLayer layer() {
                return SettingLayer.TENANT;
            }

            @Override
            public List<SettingScope> scopesFor(SettingsSubject subject) {
                return List.of(SettingScope.tenant("globex"), SettingScope.tenant(TENANT));
            }
        };

        ResolvedSettings resolved = new SettingsResolutionEngine(registry,
                List.of(new UserSettingScopeResolver(), foreignTenant),
                List.of(new ConfiguredSettingValueSource(defaults, registry)),
                new NoopSettingsMetrics())
                .resolve(SETTINGS_SUBJECT);

        assertThat(resolved.get(TestSettings.TIMEZONE)).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    private ResolvedValue<ZoneId> resolveWith(SettingScopeResolver roleResolver, ScopedValue... values) {
        return engine(allLayers(roleResolver), List.of(source(values)))
                .resolve(SETTINGS_SUBJECT)
                .resolved(TestSettings.TIMEZONE);
    }
}
