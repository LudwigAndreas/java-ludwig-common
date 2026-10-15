package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.interceptor.CallRecordingInterceptor;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;

/**
 * A repeat happens only where the contract declares it safe, and is counted.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.clients.echo.target=in-process:" + GrpcClientRetryIT.PEER,
    "ludwig.grpc.client.clients.echo.retry.initial-backoff=10ms",
    "ludwig.grpc.client.clients.echo.retry.throttling-max-tokens=1000"})
class GrpcClientRetryIT {

    static final String PEER = "retry-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private MeterRegistry meters;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    private double retries(String method) {
        var counter = meters.find(CallRecordingInterceptor.RETRIES).tags("client", "echo", "method", method).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("an IDEMPOTENT method failing UNAVAILABLE is attempted again, and each retry is counted")
    void idempotentIsRetried() {
        double before = retries("Store");
        SERVER.fail("retry-me", Status.UNAVAILABLE, Status.UNAVAILABLE);

        assertThat(EchoServiceGrpc.newBlockingStub(registry.channel("echo")).store(Echo.text("retry-me")).getText())
                .isEqualTo("retry-me");

        assertThat(SERVER.arrivals("retry-me")).hasSize(3);
        assertThat(retries("Store") - before).isEqualTo(2.0);
    }

    @Test
    @DisplayName("an IDEMPOTENCY_UNKNOWN method that reached the server is not attempted again")
    void unknownIsNotRetried() {
        SERVER.fail("once-only", Status.UNAVAILABLE);

        StatusRuntimeException failure = Echo.failure(() ->
                EchoServiceGrpc.newBlockingStub(registry.channel("echo")).append(Echo.text("once-only")));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(SERVER.arrivals("once-only")).hasSize(1);
    }

    @Test
    @DisplayName("a server pushback delays the next attempt by at least that long")
    void pushback() {
        Metadata trailers = new Metadata();
        trailers.put(ScriptedServer.PUSHBACK, "600");
        SERVER.fail("pushed-back", Status.UNAVAILABLE, trailers);

        EchoServiceGrpc.newBlockingStub(registry.channel("echo")).store(Echo.text("pushed-back"));

        List<ScriptedServer.Arrival> arrivals = SERVER.arrivals("pushed-back");
        assertThat(arrivals).hasSize(2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(arrivals.get(1).atNanos() - arrivals.get(0).atNanos()))
                .isGreaterThanOrEqualTo(600);
    }
}
