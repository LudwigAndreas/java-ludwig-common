package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.export.api.Enricher;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.ReportParameters;
import ru.ludwigandreas.export.engine.ReportParameterBinder;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportDefinitionSource;
import ru.ludwigandreas.export.api.ReportRequest;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.config.ExportConfigurationValidator;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.config.RestClientPoolSizes;
import ru.ludwigandreas.export.engine.DefaultExecutionPlanner;
import ru.ludwigandreas.export.engine.ExecutionPlanner;
import ru.ludwigandreas.export.engine.ReportScopeResolver;
import ru.ludwigandreas.export.enrich.EnrichmentSettings;
import ru.ludwigandreas.export.exception.ExportConfigurationException;
import ru.ludwigandreas.export.exception.ReportIdentityUnavailableException;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;

/**
 * Whose credentials a partner call carries, and what refuses a configuration that cannot deliver it.
 *
 * <h2>Why this is one test class across three layers</h2>
 *
 * <p>The choice is written in two places - the stage says it, the REST client sends it - and checked in
 * three: the settings resolver decides what a stage asked for, the startup validator compares that
 * against the deployment, and the planner refuses an attempt that cannot honour it. Those are useless
 * apart. A test per layer would let the pieces drift into disagreeing about what {@code REQUESTER} means
 * while every one of them passed, which is exactly the failure this feature exists to make impossible.
 */
class CallIdentityTest {

    private static final String CLIENT = "partners";

    /**
     * Binds every parameter type to {@code NoParameters}.
     *
     * <p>An anonymous class rather than a lambda: {@code bind} is generic, and a generic method cannot be
     * a lambda's functional descriptor.
     */
    private static final ReportParameterBinder BINDER = new ReportParameterBinder() {
        @Override
        @SuppressWarnings("unchecked")
        public <P extends ReportParameters> P bind(Class<P> type, Map<String, String> values) {
            return (P) NoParameters.INSTANCE;
        }
    };

    /** A stage's shape does not matter here, only its declared identity, so the enricher is a stub. */
    private static final Enricher<String, String> ENRICHER =
            (Enricher.Batched<String, String>) keys -> Map.of();

    private static EnrichmentStage<EngineFixtures.Sale, String, String> stage(CallIdentity callAs) {
        return EnrichmentStage.<EngineFixtures.Sale, String, String>of("partner", ENRICHER)
                .keyExtractor(EngineFixtures.Sale::reference)
                .merge((row, value) -> row)
                .restClient(CLIENT)
                .callAs(callAs)
                .build();
    }

    private static ReportDefinition<?, ?> definitionWith(CallIdentity callAs) {
        return EngineFixtures.definitionBuilder(List.of(), List.of())
                .stage(stage(callAs))
                .build();
    }

    /**
     * A REST client that exists, is generously sized, and authenticates the way the test says.
     *
     * <p>The pool is deliberately larger than any stage's concurrency, so the only thing a failure in
     * this class can be about is the identity.
     */
    private static RestClientPoolSizes clientWith(String authType) {
        return new RestClientPoolSizes() {
            @Override
            public OptionalInt forClient(String clientName) {
                return CLIENT.equals(clientName) ? OptionalInt.of(64) : OptionalInt.empty();
            }

            @Override
            public Optional<String> authTypeOf(String clientName) {
                return CLIENT.equals(clientName) ? Optional.ofNullable(authType) : Optional.empty();
            }
        };
    }

    /** The planner as the module wires it, with no scope resolver and no filter parser. */
    private static ExecutionPlanner planner(ReportDefinition<?, ?> definition) {
        ReportDefinitionSource source = () -> List.of(definition);
        ReportDefinitionRegistry registry = new ReportDefinitionRegistry(List.of(source),
                Map.of("csv", StandardReportFormats.CSV), (key, locale) -> true);
        return new DefaultExecutionPlanner(registry, List.of(EngineFixtures.csvFactory()),
                BINDER, ReportScopeResolver.UNRESTRICTED,
                (definitionKey, filter) -> Optional.empty(), EngineFixtures.MESSAGES,
                new ExportProperties());
    }

    private static ReportRequest csvRequest() {
        return new ReportRequest("catalog.sales", Map.of(), List.of(), null, List.<SortKey>of(),
                List.of(StandardReportFormats.CSV), Map.of(), Locale.ENGLISH, ZoneId.of("UTC"), null,
                "test-key");
    }

    private static void validate(ReportDefinition<?, ?> definition, String authType) {
        new ExportConfigurationValidator(new ExportProperties(), Set.of("xlsx", "csv"),
                Set.of("filesystem"), List.of(definition), clientWith(authType)).validate();
    }

    @Test
    @DisplayName("the default is the service account, because it is the only identity a deferred run has")
    void defaultsToTheServiceAccount() {
        EnrichmentSettings settings = new EnrichmentSettings(new ExportProperties().getEnrichment());

        assertThat(settings.callAs(stage(null))).isEqualTo(CallIdentity.SERVICE_ACCOUNT);
    }

    @Test
    @DisplayName("a stage's own choice wins over the module default, in both directions")
    void aStageOverridesTheDefault() {
        ExportProperties relayByDefault = new ExportProperties();
        relayByDefault.getEnrichment().setCallAs(CallIdentity.REQUESTER);
        EnrichmentSettings requesterDefault = new EnrichmentSettings(relayByDefault.getEnrichment());
        EnrichmentSettings serviceDefault =
                new EnrichmentSettings(new ExportProperties().getEnrichment());

        // Not a switch and an override of it: one report legitimately joins a partner it integrates with
        // under its own credentials and a partner that scopes by the caller.
        assertThat(requesterDefault.callAs(stage(CallIdentity.SERVICE_ACCOUNT)))
                .isEqualTo(CallIdentity.SERVICE_ACCOUNT);
        assertThat(serviceDefault.callAs(stage(CallIdentity.REQUESTER)))
                .isEqualTo(CallIdentity.REQUESTER);
        assertThat(requesterDefault.callAs(stage(null))).isEqualTo(CallIdentity.REQUESTER);
    }

    @Test
    @DisplayName("both identities start, when the client is configured to send the one the stage declared")
    void acceptsEitherIdentityWhenTheClientAgrees() {
        assertThatCode(() -> validate(definitionWith(CallIdentity.SERVICE_ACCOUNT),
                "oauth2-client-credentials")).doesNotThrowAnyException();
        assertThatCode(() -> validate(definitionWith(CallIdentity.REQUESTER),
                "oauth2-token-relay")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a REQUESTER stage whose client sends this service's own credentials fails startup")
    void refusesRequesterAgainstAServiceAccountClient() {
        // The silent failure this check exists for: the partner would answer with everything this
        // service may see, and nothing in the file would say the report was not scoped to the caller.
        assertThatThrownBy(() -> validate(definitionWith(CallIdentity.REQUESTER),
                "oauth2-client-credentials"))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("partner")
                .hasMessageContaining("oauth2-token-relay");
    }

    @Test
    @DisplayName("a SERVICE_ACCOUNT stage whose client relays fails startup rather than every deferred run")
    void refusesServiceAccountAgainstARelayingClient() {
        assertThatThrownBy(() -> validate(definitionWith(CallIdentity.SERVICE_ACCOUNT),
                "oauth2-token-relay"))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("no token to relay");
    }

    @Test
    @DisplayName("an auth type this module does not know is left alone rather than guessed at")
    void acceptsAnUnrecognisedAuthType() {
        // Mutual TLS with a forwarded client certificate is compatible with either identity, and there
        // are more auth types than this module can enumerate. Refusing what it cannot judge would punish
        // a deployment that authenticates in a perfectly valid way.
        assertThatCode(() -> validate(definitionWith(CallIdentity.REQUESTER), "custom"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validate(definitionWith(CallIdentity.REQUESTER), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a deferred attempt of a REQUESTER stage is refused, not quietly run as the service")
    void refusesToPlanARequesterStageOffTheRequestThread() {
        var planner = planner(definitionWith(CallIdentity.REQUESTER));

        assertThatThrownBy(() -> planner.plan(UUID.randomUUID(), csvRequest(), "alice", Set.of(), false))
                .isInstanceOf(ReportIdentityUnavailableException.class)
                .hasMessageContaining("catalog.sales")
                .hasMessageContaining("partner");
    }

    @Test
    @DisplayName("the same stage plans without complaint on the request thread")
    void plansARequesterStageOnTheRequestThread() {
        var planner = planner(definitionWith(CallIdentity.REQUESTER));

        assertThatCode(() -> planner.plan(UUID.randomUUID(), csvRequest(), "alice", Set.of(), true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a SERVICE_ACCOUNT stage plans on either thread, which is why it is the default")
    void plansAServiceAccountStageEitherWay() {
        var planner = planner(definitionWith(CallIdentity.SERVICE_ACCOUNT));

        assertThatCode(() -> planner.plan(UUID.randomUUID(), csvRequest(), "alice", Set.of(), true))
                .doesNotThrowAnyException();
        assertThatCode(() -> planner.plan(UUID.randomUUID(), csvRequest(), "alice", Set.of(), false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("StandardReportFormats is untouched by any of this, so the definition is otherwise valid")
    void theFixtureDefinitionIsOtherwiseOrdinary() {
        assertThat(definitionWith(CallIdentity.REQUESTER).getAllowedFormats())
                .containsExactly(StandardReportFormats.CSV);
    }
}
