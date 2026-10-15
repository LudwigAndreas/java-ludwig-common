package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Channel;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.config.GrpcClientAutoConfiguration;
import ru.ludwigandreas.grpc.client.error.GrpcClientException;
import ru.ludwigandreas.grpc.client.error.GrpcClientTimeoutException;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;

/**
 * Every call carries the earliest of the configured deadline and the inbound one.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.clients.echo.target=in-process:" + GrpcClientDeadlineIT.PEER,
    "ludwig.grpc.client.clients.echo.deadline=3s",
    "ludwig.grpc.client.clients.fallback.target=in-process:" + GrpcClientDeadlineIT.PEER})
class GrpcClientDeadlineIT {

    static final String PEER = "deadline-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    @DisplayName("outside any inbound call the configured deadline applies")
    void configured() {
        Echo.echo(registry.channel("echo"), "configured");

        assertThat(SERVER.arrivals("configured").get(0).deadlineMillis()).isBetween(2_000L, 3_000L);
    }

    @Test
    @DisplayName("within an inbound call with 500ms left, the outbound deadline is at most 500ms")
    void inbound() throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            Context.current().withDeadlineAfter(500, TimeUnit.MILLISECONDS, timer)
                    .call(() -> Echo.echo(registry.channel("echo"), "inbound"));
        } finally {
            timer.shutdownNow();
        }

        assertThat(SERVER.arrivals("inbound").get(0).deadlineMillis()).isBetween(1L, 500L);
    }

    @Test
    @DisplayName("with no deadline written, the built-in 5s applies")
    void builtIn() {
        Echo.echo(registry.channel("fallback"), "built-in");

        assertThat(SERVER.arrivals("built-in").get(0).deadlineMillis()).isBetween(4_000L, 5_000L);
    }

    @Test
    @DisplayName("a passed deadline is a timeout that names the client and says the request may have run")
    void timeout() {
        SERVER.pause("slow", 1_000);
        Channel channel = registry.channel("echo");

        StatusRuntimeException failure = Echo.failure(() ->
                Echo.on(channel).withDeadlineAfter(200, TimeUnit.MILLISECONDS).echo(Echo.text("slow")));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        assertThat(GrpcClientException.from(failure)).isInstanceOf(GrpcClientTimeoutException.class)
                .hasMessageContaining("'echo'").hasMessageContaining("may have been processed");
    }

    @Test
    @DisplayName("a deadline of zero fails startup naming the client")
    void zero() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrpcClientAutoConfiguration.class))
                .withPropertyValues("ludwig.grpc.client.clients.instant.target=in-process:x",
                        "ludwig.grpc.client.clients.instant.deadline=0s")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("\"instant\" has deadline"));
    }
}
