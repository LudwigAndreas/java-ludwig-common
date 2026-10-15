package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import ru.ludwigandreas.grpc.server.error.GrpcServerSaturatedException;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.integration.support.EchoService;
import ru.ludwigandreas.grpc.server.server.GrpcServerLifecycle;

/**
 * The limits that hold with no configuration, and what a caller is told when one is hit.
 */
class GrpcServerLimitsIT {

    private static final int FIVE_MEBIBYTES = 5 * 1024 * 1024;

    @Test
    @DisplayName("the defaults are the ones the design states")
    void defaults() {
        GrpcServerProperties defaults = new GrpcServerProperties();

        assertThat(defaults.getMaxInboundMessageSize()).isEqualTo(DataSize.ofMegabytes(4));
        assertThat(defaults.getMaxInboundMetadataSize()).isEqualTo(DataSize.ofKilobytes(8));
        assertThat(defaults.getMaxConnectionAge()).isEqualTo(Duration.ofMinutes(30));
        assertThat(defaults.getMaxConnectionAgeGrace()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.getMaxConcurrentCallsPerConnection()).isEqualTo(100);
        assertThat(defaults.getKeepalive().getPermitTime()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.getExecutor().getThreads()).isEqualTo(64);
        assertThat(defaults.getExecutor().getQueueCapacity()).isEqualTo(256);
        assertThat(defaults.getShutdownGrace()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.getReflection().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a message over the default 4 MiB is RESOURCE_EXHAUSTED and the handler does not run")
    void oversizedMessage() {
        GrpcServerLifecycleIT.RUNNER.withUserConfiguration(EchoApplication.class).run(context -> {
            ManagedChannel channel =
                    GrpcServerLifecycleIT.channelTo(context.getBean(GrpcServerLifecycle.class).port());
            try {
                EchoRequest oversized = EchoRequest.newBuilder().setText("big")
                        .setPayload(ByteString.copyFrom(new byte[FIVE_MEBIBYTES])).build();

                StatusRuntimeException refused = catchThrowableOfType(
                        () -> Calls.stub(channel).echo(oversized), StatusRuntimeException.class);

                assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
                assertThat(context.getBean(EchoService.class).invocations("big")).isZero();
            } finally {
                channel.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("with every handler thread busy and the queue full, a further call is RESOURCE_EXHAUSTED")
    void saturatedExecutor() {
        GrpcServerLifecycleIT.RUNNER.withUserConfiguration(EchoApplication.class)
                .withPropertyValues(
                        "ludwig.grpc.server.executor.threads=1", "ludwig.grpc.server.executor.queue-capacity=1")
                .run(context -> {
                    EchoService service = context.getBean(EchoService.class);
                    ManagedChannel channel =
                            GrpcServerLifecycleIT.channelTo(context.getBean(GrpcServerLifecycle.class).port());
                    try {
                        EchoServiceGrpc.EchoServiceFutureStub async = EchoServiceGrpc.newFutureStub(channel);
                        service.arm();
                        // Holds the one handler thread.
                        ListenableFuture<EchoReply> running = async.echo(Calls.text(EchoService.BLOCK));
                        assertThat(service.awaitEntered()).isTrue();
                        // Takes the one queue slot. The pause is the only way to know it got there:
                        // the queue is not observable from outside the server.
                        ListenableFuture<EchoReply> queued = async.echo(Calls.text("queued"));
                        TimeUnit.MILLISECONDS.sleep(500);

                        StatusRuntimeException refused = catchThrowableOfType(
                                () -> Calls.stub(channel).echo(Calls.text("refused")), StatusRuntimeException.class);

                        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
                        assertThat(new GrpcStatusCodec("test").decode(refused).orElseThrow().problemCode())
                                .contains(GrpcServerSaturatedException.CODE);
                        assertThat(service.invocations("refused")).isZero();

                        service.release();
                        assertThat(running.get(5, TimeUnit.SECONDS).getText()).isEqualTo(EchoService.BLOCK);
                        assertThat(queued.get(5, TimeUnit.SECONDS).getText()).isEqualTo("queued");
                    } finally {
                        channel.shutdownNow();
                    }
                });
    }
}
