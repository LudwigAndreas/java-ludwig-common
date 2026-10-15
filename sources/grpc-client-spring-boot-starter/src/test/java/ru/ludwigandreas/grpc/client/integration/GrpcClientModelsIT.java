package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.LudwigGrpcClient;
import ru.ludwigandreas.grpc.client.config.GrpcClientAutoConfiguration;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.interceptor.ObservationInterceptor;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;

/**
 * One client, three ways to call it, one behaviour.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.clients.echo.target=in-process:" + GrpcClientModelsIT.PEER,
    "ludwig.grpc.client.clients.echo.deadline=3s",
    "ludwig.grpc.client.clients.echo.auth.type=bearer",
    "ludwig.grpc.client.clients.echo.auth.token=t-models"})
@Import(GrpcClientModelsIT.Consumers.class)
class GrpcClientModelsIT {

    static final String PEER = "models-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private EchoConsumer consumer;

    @Autowired
    @Qualifier("echo")
    private Channel qualifiedChannel;

    @Autowired
    @LudwigGrpcClient("echo")
    private Channel annotatedChannel;

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private MeterRegistry meters;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    @DisplayName("an injected stub, a stub on the injected channel and one on the registry's behave the same")
    void threeWays() throws Exception {
        consumer.fromParameter.echo(Echo.text("way-1"));
        consumer.fromField.echo(Echo.text("way-2")).get(5, TimeUnit.SECONDS);
        Echo.echo(qualifiedChannel, "way-3");
        Echo.echo(annotatedChannel, "way-4");
        Echo.echo(registry.channel("echo"), "way-5");

        for (String text : new String[] {"way-1", "way-2", "way-3", "way-4", "way-5"}) {
            ScriptedServer.Arrival arrival = SERVER.arrivals(text).get(0);
            assertThat(arrival.authorization()).as(text).isEqualTo("Bearer t-models");
            assertThat(arrival.deadlineMillis()).as(text).isNotNull().isBetween(1L, 3_000L);
        }
        assertThat(meters.get(ObservationInterceptor.REQUESTS)
                .tags("client", "echo", "service", "ludwig.echo.v1.EchoService", "method", "Echo", "status", "OK")
                .timer().count()).isEqualTo(5);
    }

    @Test
    @DisplayName("an unknown client fails immediately and lists the configured ones")
    void unknownClient() {
        assertThatThrownBy(() -> registry.channel("nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[echo]");
    }

    @Test
    @DisplayName("with no client configured nothing is built and no channel bean exists")
    void noClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrpcClientAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GrpcChannelRegistry.class).names()).isEmpty();
                    assertThat(context.getBeanNamesForType(Channel.class)).isEmpty();
                });
    }

    @Test
    @DisplayName("a stub bound to an unconfigured client fails startup naming the injection point and the client")
    void stubForUnknownClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrpcClientAutoConfiguration.class))
                .withPropertyValues("ludwig.grpc.client.clients.echo.target=in-process:x")
                .withUserConfiguration(MisboundConsumer.class)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("MisboundConsumer.stub")
                        .hasStackTraceContaining("\"missing\""));
    }

    /** Takes its stubs the way a service would: a {@code @Bean} parameter and a field. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Consumers {

        @Bean
        EchoConsumer echoConsumer(@LudwigGrpcClient("echo") EchoServiceGrpc.EchoServiceBlockingStub stub) {
            return new EchoConsumer(stub);
        }
    }

    static class EchoConsumer {

        final EchoServiceGrpc.EchoServiceBlockingStub fromParameter;

        @LudwigGrpcClient("echo")
        EchoServiceGrpc.EchoServiceFutureStub fromField;

        EchoConsumer(EchoServiceGrpc.EchoServiceBlockingStub fromParameter) {
            this.fromParameter = fromParameter;
        }
    }

    static class MisboundConsumer {

        @LudwigGrpcClient("missing")
        EchoServiceGrpc.EchoServiceBlockingStub stub;
    }
}
