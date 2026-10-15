package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;

/**
 * Audit through the platform's one sink: failures and refusals always, successes as sampled.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.clients.audited.target=in-process:" + GrpcClientAuditIT.PEER,
    "ludwig.grpc.client.clients.audited.retry.enabled=false",
    "ludwig.grpc.client.clients.audited.audit.enabled=true",
    "ludwig.grpc.client.clients.audited.audit.success-sampling=0"})
class GrpcClientAuditIT {

    static final String PEER = "audit-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private ClientTestApplication.RecordingAuditSink audit;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @BeforeEach
    void reset() {
        audit.events.clear();
        breakers.circuitBreaker("grpc-audited").reset();
    }

    private java.util.List<AuditEvent> outbound() {
        return audit.events.stream().filter(event -> AuditCategories.OUTBOUND_CALL.equals(event.category())).toList();
    }

    @Test
    @DisplayName("with sampling 0, a success and a failure record exactly one event - the failure")
    void failuresAlways() {
        Echo.echo(registry.channel("audited"), "fine");
        SERVER.fail("broken", Status.INTERNAL);
        Echo.failure(() -> Echo.echo(registry.channel("audited"), "broken"));

        assertThat(outbound()).singleElement().satisfies(event ->
                assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.FAILURE));
    }

    @Test
    @DisplayName("a call refused by the open breaker is recorded as DENIED")
    void refusalIsDenied() {
        breakers.circuitBreaker("grpc-audited").transitionToOpenState();

        Echo.failure(() -> Echo.echo(registry.channel("audited"), "refused"));

        assertThat(outbound()).singleElement().satisfies(event ->
                assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.DENIED));
    }
}
