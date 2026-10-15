package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import ru.ludwigandreas.grpc.server.server.GrpcServerLifecycle;

/**
 * {@code grpc.health.v1.Health} agrees with Spring's readiness, and turns first on shutdown.
 */
class GrpcServerHealthIT {

    private static final String SERVICE = "ludwig.echo.v1.EchoService";

    private static ServingStatus check(ManagedChannel channel, String service) {
        return HealthGrpc.newBlockingStub(channel)
                .check(HealthCheckRequest.newBuilder().setService(service).build()).getStatus();
    }

    @Test
    @DisplayName("health follows the readiness state, for the server and for each service by name")
    void followsReadiness() {
        GrpcServerLifecycleIT.RUNNER.withUserConfiguration(EchoApplication.class).run(context -> {
            ManagedChannel channel =
                    GrpcServerLifecycleIT.channelTo(context.getBean(GrpcServerLifecycle.class).port());
            try {
                AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
                assertThat(check(channel, "")).isEqualTo(ServingStatus.SERVING);
                assertThat(check(channel, SERVICE)).isEqualTo(ServingStatus.SERVING);

                AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
                assertThat(check(channel, "")).isEqualTo(ServingStatus.NOT_SERVING);
                assertThat(check(channel, SERVICE)).isEqualTo(ServingStatus.NOT_SERVING);
            } finally {
                channel.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("shutdown announces NOT_SERVING to a watcher before the server stops")
    void notServingFirstOnShutdown() {
        GrpcServerLifecycleIT.RUNNER.withUserConfiguration(EchoApplication.class)
                .withPropertyValues("ludwig.grpc.server.shutdown-grace=1s")
                .run(context -> {
                    GrpcServerLifecycle lifecycle = context.getBean(GrpcServerLifecycle.class);
                    ManagedChannel channel = GrpcServerLifecycleIT.channelTo(lifecycle.port());
                    List<ServingStatus> watched = new CopyOnWriteArrayList<>();
                    try {
                        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
                        HealthGrpc.newStub(channel).watch(HealthCheckRequest.getDefaultInstance(),
                                new StreamObserver<>() {
                                    @Override
                                    public void onNext(HealthCheckResponse response) {
                                        watched.add(response.getStatus());
                                    }

                                    @Override
                                    public void onError(Throwable failure) {
                                    }

                                    @Override
                                    public void onCompleted() {
                                    }
                                });
                        await().atMost(Duration.ofSeconds(5))
                                .untilAsserted(() -> assertThat(watched).containsExactly(ServingStatus.SERVING));

                        lifecycle.stop();

                        assertThat(watched).containsExactly(ServingStatus.SERVING, ServingStatus.NOT_SERVING);
                    } finally {
                        channel.shutdownNow();
                    }
                });
    }
}
