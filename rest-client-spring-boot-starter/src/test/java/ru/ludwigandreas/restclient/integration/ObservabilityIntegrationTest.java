package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.spi.AuditEventEmitter;
import ru.ludwigandreas.restclient.spi.OutboundCallAudit;
import ru.ludwigandreas.restclient.spi.OutboundRequest;
import ru.ludwigandreas.restclient.spi.OutboundResponse;
import ru.ludwigandreas.restclient.spi.RestClientListener;

/** Listeners, metrics and audit: the three things that must never be able to fail a call. */
class ObservabilityIntegrationTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("a listener sees the request, the response and the retry, with the URI template")
    void listenersSeeTheWholeCall() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(props())
                .withUserConfiguration(RecordingListenerConfiguration.class)
                .run(context -> {
                    context.getBean(RestClientRegistry.class).rest("billing")
                            .get().uri("/invoices/{id}", "42").retrieve().body(String.class);

                    RecordingListener listener = context.getBean(RecordingListener.class);
                    assertThat(listener.events).contains(
                            "request:/invoices/{id}:1",
                            "response:503:1",
                            "retry:2:status:503",
                            "request:/invoices/{id}:2",
                            "response:200:2");
                });
    }

    @Test
    @DisplayName("a listener that throws is counted and the call still succeeds")
    void aThrowingListenerCannotBreakTheCall() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(props())
                .withUserConfiguration(ThrowingListenerConfiguration.class)
                .run(context -> {
                    String body = context.getBean(RestClientRegistry.class).rest("billing")
                            .get().uri("/x").retrieve().body(String.class);

                    assertThat(body).isEqualTo("ok");
                    assertThat(context.getBean(MeterRegistry.class)
                            .find("ludwig.restclient.listener.failures").counter()).isNotNull();
                });
    }

    @Test
    @DisplayName("a retry is counted with a bounded reason tag")
    void countsRetries() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(props())
                .withUserConfiguration(MeterRegistryConfiguration.class)
                .run(context -> {
                    context.getBean(RestClientRegistry.class).rest("billing")
                            .get().uri("/x").retrieve().body(String.class);

                    var counter = context.getBean(MeterRegistry.class)
                            .find("ludwig.restclient.retries")
                            .tag("client", "billing")
                            .tag("reason", "status:503")
                            .counter();
                    assertThat(counter).isNotNull();
                    assertThat(counter.count()).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("a breaker transition is counted and handed to the listeners")
    void reportsCircuitBreakerTransitions() {
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.enabled=true",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.sliding-window-size=4",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".minimum-number-of-calls=4",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".failure-rate-threshold=50",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".wait-duration-in-open-state=60s"));

        RestClientTestSupport.runner(properties)
                .withUserConfiguration(TransitionListenerConfiguration.class)
                .run(context -> {
                    var client = context.getBean(RestClientRegistry.class).rest("billing");
                    for (int i = 0; i < 4; i++) {
                        assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                                .isInstanceOf(RuntimeException.class);
                    }

                    assertThat(context.getBean(RecordingListener.class).events)
                            .contains("breaker:CLOSED->OPEN");
                    var counter = context.getBean(MeterRegistry.class)
                            .find("ludwig.restclient.circuitbreaker.transitions")
                            .tag("client", "billing")
                            .tag("to", "OPEN")
                            .counter();
                    assertThat(counter).isNotNull();
                    assertThat(counter.count()).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("an audit record carries the templated URI and never the expanded one")
    void auditRecordsTheTemplateNotThePath() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.audit.enabled=true"));

        RestClientTestSupport.runner(properties)
                .withUserConfiguration(RecordingAuditConfiguration.class)
                .run(context -> {
                    context.getBean(RestClientRegistry.class).rest("billing")
                            .get().uri("/invoices/{id}", "customer-4711").retrieve().body(String.class);

                    RecordingEmitter emitter = context.getBean(RecordingEmitter.class);
                    assertThat(emitter.records).hasSize(1);
                    OutboundCallAudit audit = emitter.records.get(0);
                    assertThat(audit.uriTemplate()).isEqualTo("/invoices/{id}");
                    assertThat(audit.clientName()).isEqualTo("billing");
                    assertThat(audit.outcome()).isEqualTo("SUCCESS");
                    assertThat(audit.statusCode()).isEqualTo(200);
                });
    }

    @Test
    @DisplayName("a failure is always audited, whatever the sampling probability")
    void auditsEveryFailureRegardlessOfSampling() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.audit.enabled=true",
                "ludwig.rest-client.clients.billing.audit.sampling-probability=0.0",
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1"));

        RestClientTestSupport.runner(properties)
                .withUserConfiguration(RecordingAuditConfiguration.class)
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(RestClientRegistry.class).rest("billing")
                            .get().uri("/x").retrieve().body(String.class))
                            .isInstanceOf(RuntimeException.class);

                    RecordingEmitter emitter = context.getBean(RecordingEmitter.class);
                    assertThat(emitter.records).hasSize(1);
                    assertThat(emitter.records.get(0).outcome()).isEqualTo("SERVER_ERROR");
                });
    }

    private String[] props() {
        return RestClientTestSupport.props(
                RestClientTestSupport.fastClient("billing", server.url("/").toString(),
                        "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=2"));
    }

    /** Records the lifecycle callbacks in the order they arrive. */
    static class RecordingListener implements RestClientListener {

        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onRequest(OutboundRequest request) {
            events.add("request:" + request.uriTemplate() + ":" + request.attempt());
        }

        @Override
        public void onResponse(OutboundRequest request, OutboundResponse response) {
            events.add("response:" + response.statusCode() + ":" + request.attempt());
        }

        @Override
        public void onRetry(OutboundRequest request, int nextAttempt, long waitMillis, String cause) {
            events.add("retry:" + nextAttempt + ":" + cause);
        }

        @Override
        public void onCircuitBreakerStateChange(String clientName, String fromState, String toState) {
            events.add("breaker:" + fromState + "->" + toState);
        }
    }

    /** Collects audit records instead of writing them anywhere. */
    static class RecordingEmitter implements AuditEventEmitter {

        private final List<OutboundCallAudit> records = new CopyOnWriteArrayList<>();

        @Override
        public void emit(OutboundCallAudit audit) {
            records.add(audit);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingListenerConfiguration {

        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ThrowingListenerConfiguration extends MeterRegistryConfiguration {

        @Bean
        RestClientListener throwingListener() {
            return new RestClientListener() {
                @Override
                public void onRequest(OutboundRequest request) {
                    throw new IllegalStateException("the dashboard integration is broken");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TransitionListenerConfiguration extends MeterRegistryConfiguration {

        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingAuditConfiguration {

        @Bean
        RecordingEmitter recordingEmitter() {
            return new RecordingEmitter();
        }
    }
}
