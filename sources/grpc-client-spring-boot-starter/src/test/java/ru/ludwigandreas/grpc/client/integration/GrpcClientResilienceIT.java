package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ListenableFuture;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.error.GrpcClientCallNotPermittedException;
import ru.ludwigandreas.grpc.client.error.GrpcClientException;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;

/**
 * One permit and one result per logical call, failures classified, clients isolated.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.defaults.retry.initial-backoff=10ms",
    "ludwig.grpc.client.defaults.retry.throttling-max-tokens=1000",
    "ludwig.grpc.client.clients.echo.target=in-process:" + GrpcClientResilienceIT.PEER,
    "ludwig.grpc.client.clients.saturating.target=in-process:" + GrpcClientResilienceIT.PEER,
    "ludwig.grpc.client.clients.saturating.resilience.bulkhead.max-concurrent-calls=1",
    "ludwig.grpc.client.clients.healthy.target=in-process:" + GrpcClientResilienceIT.PEER})
class GrpcClientResilienceIT {

    static final String PEER = "resilience-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @Autowired
    private MeterRegistry meters;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @BeforeEach
    void reset() {
        breakers.circuitBreaker("grpc-echo").reset();
        breakers.circuitBreaker("grpc-saturating").reset();
    }

    @Test
    @DisplayName("a call that succeeds on its third attempt is one successful call to the breaker")
    void oneResultPerLogicalCall() {
        SERVER.fail("third-time", Status.UNAVAILABLE, Status.UNAVAILABLE);

        EchoServiceGrpc.newBlockingStub(registry.channel("echo")).store(Echo.text("third-time"));

        CircuitBreaker.Metrics metrics = breakers.circuitBreaker("grpc-echo").getMetrics();
        assertThat(SERVER.arrivals("third-time")).hasSize(3);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("an open breaker refuses the call, nothing reaches the peer, and the refusal is counted")
    void openBreaker() {
        breakers.circuitBreaker("grpc-echo").transitionToOpenState();

        StatusRuntimeException refused = Echo.failure(() -> Echo.echo(registry.channel("echo"), "refused"));

        assertThat(GrpcClientException.from(refused)).isInstanceOfSatisfying(
                GrpcClientCallNotPermittedException.class,
                notPermitted -> assertThat(notPermitted.policy()).isEqualTo("circuit-breaker"));
        assertThat(SERVER.arrivals("refused")).isEmpty();
        assertThat(meters.get("ludwig.grpcclient.calls.notpermitted")
                .tags("client", "echo", "policy", "circuit-breaker").counter().count()).isPositive();
    }

    @Test
    @DisplayName("INVALID_ARGUMENT, NOT_FOUND and PERMISSION_DENIED never open the breaker")
    void callerSideCodesDoNotCount() {
        for (Status status : new Status[] {Status.INVALID_ARGUMENT, Status.NOT_FOUND, Status.PERMISSION_DENIED}) {
            SERVER.alwaysFail("wrong-" + status.getCode(), status, 120);
            for (int i = 0; i < 40; i++) {
                Echo.failure(() -> Echo.echo(registry.channel("echo"), "wrong-" + status.getCode()));
            }
        }

        CircuitBreaker breaker = breakers.circuitBreaker("grpc-echo");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("one client saturated and open leaves another client to the same peer untouched")
    void isolation() throws Exception {
        SERVER.pause("hold", 700);
        ListenableFuture<EchoReply> holding =
                EchoServiceGrpc.newFutureStub(registry.channel("saturating")).echo(Echo.text("hold"));
        Thread.sleep(200);

        StatusRuntimeException full = Echo.failure(() -> Echo.echo(registry.channel("saturating"), "second"));
        assertThat(GrpcClientException.from(full)).isInstanceOfSatisfying(GrpcClientCallNotPermittedException.class,
                notPermitted -> assertThat(notPermitted.policy()).isEqualTo("bulkhead"));
        breakers.circuitBreaker("grpc-saturating").transitionToOpenState();

        assertThat(Echo.echo(registry.channel("healthy"), "other-client").getText()).isEqualTo("other-client");
        holding.get(5, TimeUnit.SECONDS);
    }
}
