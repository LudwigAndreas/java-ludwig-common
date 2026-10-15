package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.server.config.GrpcServerAutoConfiguration;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import ru.ludwigandreas.grpc.server.config.GrpcServerSecurityAutoConfiguration;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.integration.support.EchoService;
import ru.ludwigandreas.grpc.server.server.GrpcServerLifecycle;
import ru.ludwigandreas.webcore.config.WebCorePreferenceAutoConfiguration;
import ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration;

/**
 * When the server binds, when it does not, and how it stops.
 */
class GrpcServerLifecycleIT {

    static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebCoreProblemAutoConfiguration.class, WebCorePreferenceAutoConfiguration.class,
                    GrpcServerAutoConfiguration.class, GrpcServerSecurityAutoConfiguration.class))
            .withPropertyValues(
                    "ludwig.grpc.server.port=0",
                    "ludwig.grpc.server.address=127.0.0.1",
                    "ludwig.grpc.server.security.enabled=false");

    static ManagedChannel channelTo(int port) {
        return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    }

    @Test
    @DisplayName("the default port is 9090")
    void defaultPort() {
        assertThat(new GrpcServerProperties().getPort()).isEqualTo(9090);
    }

    @Test
    @DisplayName("the starter with no service binds nothing and the application starts")
    void noServiceNoBind() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            GrpcServerLifecycle lifecycle = context.getBean(GrpcServerLifecycle.class);
            assertThat(lifecycle.isRunning()).isFalse();
            assertThat(lifecycle.port()).isEqualTo(-1);
        });
    }

    @Test
    @DisplayName("a declared service is reachable on the configured port")
    void serviceIsServed() {
        RUNNER.withUserConfiguration(EchoApplication.class).run(context -> {
            GrpcServerLifecycle lifecycle = context.getBean(GrpcServerLifecycle.class);
            assertThat(lifecycle.isRunning()).isTrue();
            ManagedChannel channel = channelTo(lifecycle.port());
            try {
                assertThat(Calls.stub(channel).echo(Calls.text("hello")).getText()).isEqualTo("hello");
                assertThat(Calls.stub(channel).stream(Calls.text("s").toBuilder().setRepeat(3).build()))
                        .toIterable().hasSize(3);
            } finally {
                channel.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("enabled=false binds nothing even with a service declared")
    void disabledNoBind() {
        RUNNER.withUserConfiguration(EchoApplication.class)
                .withPropertyValues("ludwig.grpc.server.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GrpcServerLifecycle.class);
                });
    }

    @Test
    @DisplayName("shutdown lets a call in flight finish and refuses a new one")
    void shutdownDrains() {
        RUNNER.withUserConfiguration(EchoApplication.class).run(context -> {
            GrpcServerLifecycle lifecycle = context.getBean(GrpcServerLifecycle.class);
            EchoService service = context.getBean(EchoService.class);
            int port = lifecycle.port();
            ManagedChannel channel = channelTo(port);
            ManagedChannel late = channelTo(port);
            try {
                service.arm();
                ListenableFuture<EchoReply> inFlight =
                        EchoServiceGrpc.newFutureStub(channel).echo(Calls.text(EchoService.BLOCK));
                assertThat(service.awaitEntered()).isTrue();

                CompletableFuture<Void> stopping = CompletableFuture.runAsync(lifecycle::stop);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThatThrownBy(() -> Calls.stub(late).echo(Calls.text("late")))
                                .isInstanceOfSatisfying(StatusRuntimeException.class, refused ->
                                        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE)));
                assertThat(stopping).isNotDone();

                service.release();
                assertThat(inFlight.get(5, TimeUnit.SECONDS).getText()).isEqualTo(EchoService.BLOCK);
                stopping.get(5, TimeUnit.SECONDS);
                assertThat(lifecycle.isRunning()).isFalse();
            } finally {
                channel.shutdownNow();
                late.shutdownNow();
            }
        });
    }
}
