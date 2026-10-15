package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.interceptor.AccessLogInterceptor;
import ru.ludwigandreas.grpc.server.interceptor.ObservationInterceptor;
import ru.ludwigandreas.grpc.server.test.LudwigGrpcServerTest;

/**
 * The request timer, the no-deadline counter and the access log.
 */
@LudwigGrpcServerTest(properties = "ludwig.grpc.server.security.enabled=false")
@Import(GrpcServerObservabilityIT.Meters.class)
@ExtendWith(OutputCaptureExtension.class)
class GrpcServerObservabilityIT {

    private static final String SERVICE = "ludwig.echo.v1.EchoService";

    @Autowired
    private ManagedChannel channel;

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("every call is timed under ludwig.grpcserver.requests with service, method and status")
    void requestTimer() {
        Calls.stub(channel).echo(Calls.text("timed"));

        assertThat(meters.get(ObservationInterceptor.REQUESTS)
                .tags("service", SERVICE, "method", "Echo", "status", "OK").timer().count()).isPositive();
    }

    @Test
    @DisplayName("a call without a deadline is counted, one with a deadline is not")
    void noDeadlineCounter() {
        double before = noDeadline("Store");
        Calls.stub(channel).store(Calls.text("no-deadline"));
        Calls.stub(channel).withDeadlineAfter(5, TimeUnit.SECONDS).store(Calls.text("with-deadline"));

        assertThat(noDeadline("Store") - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the access log names method and status on the service's logger and never the payload")
    void accessLog(CapturedOutput output) {
        Calls.stub(channel, "x-correlation-id", "corr-log").append(Calls.text("payload-must-not-be-logged"));

        assertThat(output).contains("ludwig.grpcserver." + SERVICE).contains("Append OK")
                .doesNotContain("payload-must-not-be-logged");
    }

    private double noDeadline(String method) {
        var counter = meters.find(AccessLogInterceptor.NO_DEADLINE)
                .tags("service", SERVICE, "method", method).counter();
        return counter == null ? 0 : counter.count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Meters {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry(MeterRegistry meters) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            return registry;
        }
    }
}
