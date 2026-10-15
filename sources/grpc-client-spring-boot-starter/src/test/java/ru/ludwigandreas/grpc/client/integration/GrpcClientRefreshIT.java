package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ListenableFuture;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.properties.GrpcClientProperties;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;

/**
 * The refreshable keys take effect without a restart; a call in flight keeps what it started with.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.clients.echo.target=in-process:" + GrpcClientRefreshIT.PEER,
    "ludwig.grpc.client.clients.echo.deadline=3s",
    "ludwig.grpc.client.clients.echo.resilience.rate-limiter.limit-for-period=50"})
class GrpcClientRefreshIT {

    static final String PEER = "refresh-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private GrpcClientProperties properties;

    @Autowired
    private RateLimiterRegistry rateLimiters;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    @DisplayName("a rebound deadline and rate limit apply to new calls; the call in flight keeps its deadline")
    void refresh() throws Exception {
        SERVER.pause("in-flight", 1_500);
        ListenableFuture<EchoReply> inFlight =
                EchoServiceGrpc.newFutureStub(registry.channel("echo")).echo(Echo.text("in-flight"));

        properties.getClients().get("echo").setDeadline(Duration.ofMillis(500));
        properties.getClients().get("echo").getResilience().getRateLimiter().setLimitForPeriod(7);
        Thread.sleep(1_100);
        Echo.echo(registry.channel("echo"), "after");

        assertThat(SERVER.arrivals("after").get(0).deadlineMillis()).isBetween(1L, 500L);
        assertThat(rateLimiters.rateLimiter("grpc-echo").getRateLimiterConfig().getLimitForPeriod()).isEqualTo(7);
        assertThat(inFlight.get(5, TimeUnit.SECONDS).getText()).isEqualTo("in-flight");
    }
}
