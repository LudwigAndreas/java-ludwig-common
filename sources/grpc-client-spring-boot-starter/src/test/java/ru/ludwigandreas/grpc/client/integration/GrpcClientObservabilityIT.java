package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.interceptor.ObservationInterceptor;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;

/**
 * The request timer and the per-client log line, with no payload in it.
 */
@LudwigGrpcClientTest(properties = "ludwig.grpc.client.clients.notifications.target=in-process:"
        + GrpcClientObservabilityIT.PEER)
@ExtendWith(OutputCaptureExtension.class)
class GrpcClientObservabilityIT {

    static final String PEER = "observability-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private MeterRegistry meters;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    @DisplayName("a call is timed with client, service, method and status, and logged without its payload")
    void timedAndLogged(CapturedOutput output) {
        Echo.echo(registry.channel("notifications"), "field-value-never-logged");

        assertThat(meters.get(ObservationInterceptor.REQUESTS).tags("client", "notifications",
                        "service", "ludwig.echo.v1.EchoService", "method", "Echo", "status", "OK")
                .timer().count()).isEqualTo(1);
        assertThat(output).contains("ludwig.grpcclient.notifications").contains("Echo OK").contains("attempts=1")
                .doesNotContain("field-value-never-logged");
    }
}
