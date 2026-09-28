package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.restclient.config.ClientMode;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.ClientPropertiesMerger;
import ru.ludwigandreas.restclient.config.TransportEngine;

/**
 * The inheritance rules, which are the part of this module a deployment gets wrong most often.
 *
 * <p>Each test names the rule it pins rather than the method it calls, because the rules are what
 * the README promises and what somebody editing the merger has to keep true.
 */
class ClientPropertiesMergerTest {

    @Test
    @DisplayName("a client inherits every key it does not mention")
    void inheritsUnmentionedKeys() {
        ClientProperties defaults = new ClientProperties();
        defaults.setConnectTimeout(Duration.ofSeconds(5));
        defaults.setReadTimeout(Duration.ofSeconds(30));

        ClientProperties client = new ClientProperties();
        client.setBaseUrl("https://billing.internal");

        ClientProperties merged = ClientPropertiesMerger.resolve(defaults, client);

        assertThat(merged.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(merged.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("a key the client states overrides the defaults block")
    void clientOverridesDefaults() {
        ClientProperties defaults = new ClientProperties();
        defaults.setReadTimeout(Duration.ofSeconds(10));

        ClientProperties client = new ClientProperties();
        client.setBaseUrl("https://billing.internal");
        client.setReadTimeout(Duration.ofSeconds(120));

        assertThat(ClientPropertiesMerger.resolve(defaults, client).getReadTimeout())
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("built-in defaults apply where neither layer states anything")
    void builtInDefaultsApply() {
        ClientProperties merged = ClientPropertiesMerger.resolve(new ClientProperties(), client());

        assertThat(merged.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(merged.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(merged.getMode()).isEqualTo(ClientMode.SYNC);
        assertThat(merged.getTransport()).isEqualTo(TransportEngine.HTTP_CLIENT);
        assertThat(merged.getResilience().getRetry().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a list a client states replaces the inherited one rather than extending it")
    void listsAreReplacedNotConcatenated() {
        ClientProperties defaults = new ClientProperties();
        defaults.getResilience().getRetry().setRetryOnStatus(List.of(500, 502, 503));

        ClientProperties client = client();
        client.getResilience().getRetry().setRetryOnStatus(List.of(503));

        assertThat(ClientPropertiesMerger.resolve(defaults, client)
                .getResilience().getRetry().getRetryOnStatus())
                .containsExactly(503);
    }

    @Test
    @DisplayName("default headers merge key by key, the client winning per key")
    void mapsMergeKeyByKey() {
        ClientProperties defaults = new ClientProperties();
        defaults.setDefaultHeaders(Map.of("X-Platform", "ludwig", "Accept", "application/json"));

        ClientProperties client = client();
        client.setDefaultHeaders(Map.of("Accept", "application/xml"));

        Map<String, String> merged =
                ClientPropertiesMerger.resolve(defaults, client).getDefaultHeaders();

        assertThat(merged).containsEntry("X-Platform", "ludwig")
                .containsEntry("Accept", "application/xml");
    }

    @Test
    @DisplayName("additional-* redaction lists are concatenated, not replaced")
    void additionalRedactionListsAreAdditive() {
        ClientProperties defaults = new ClientProperties();
        defaults.getLogging().setAdditionalRedactedHeaders(List.of("X-Platform-Secret"));

        ClientProperties client = client();
        client.getLogging().setAdditionalRedactedHeaders(List.of("X-Partner-Signature"));

        assertThat(ClientPropertiesMerger.resolve(defaults, client)
                .getLogging().getAdditionalRedactedHeaders())
                .containsExactly("X-Platform-Secret", "X-Partner-Signature");
    }

    @Test
    @DisplayName("base-url is never inherited from the defaults block")
    void baseUrlIsNotInherited() {
        ClientProperties defaults = new ClientProperties();
        defaults.setBaseUrl("https://wrong.internal");

        assertThat(ClientPropertiesMerger.resolve(defaults, client()).getBaseUrl())
                .isEqualTo("https://billing.internal");
    }

    @Test
    @DisplayName("auth.relay-enabled is never inherited - relaying a user token is per client")
    void relayEnabledIsNotInherited() {
        ClientProperties defaults = new ClientProperties();
        defaults.getAuth().setRelayEnabled(true);

        assertThat(ClientPropertiesMerger.resolve(defaults, client()).getAuth().getRelayEnabled())
                .isNull();
    }

    @Test
    @DisplayName("mode: async forces the reactor-netty transport regardless of what was configured")
    void asyncForcesReactorNetty() {
        ClientProperties client = client();
        client.setMode(ClientMode.ASYNC);
        client.setTransport(TransportEngine.APACHE);

        assertThat(ClientPropertiesMerger.resolve(new ClientProperties(), client).getTransport())
                .isEqualTo(TransportEngine.REACTOR_NETTY);
    }

    @Test
    @DisplayName("the slow-call threshold defaults to the client's own read timeout")
    void slowCallThresholdFollowsReadTimeout() {
        ClientProperties client = client();
        client.setReadTimeout(Duration.ofSeconds(120));

        assertThat(ClientPropertiesMerger.resolve(new ClientProperties(), client)
                .getResilience().getCircuitBreaker().getSlowCallDurationThreshold())
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("a refreshable auth type defaults to retrying once after a 401; a static one does not")
    void retryOnUnauthorizedFollowsAuthType() {
        ClientProperties refreshable = client();
        refreshable.getAuth().setType("oauth2-client-credentials");
        ClientProperties statik = client();
        statik.getAuth().setType("api-key");

        assertThat(ClientPropertiesMerger.resolve(new ClientProperties(), refreshable)
                .getAuth().getRetryOnUnauthorized()).isTrue();
        assertThat(ClientPropertiesMerger.resolve(new ClientProperties(), statik)
                .getAuth().getRetryOnUnauthorized()).isFalse();
    }

    private ClientProperties client() {
        ClientProperties client = new ClientProperties();
        client.setBaseUrl("https://billing.internal");
        return client;
    }
}
