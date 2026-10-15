package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import ru.ludwigandreas.grpc.core.problem.DecodedProblem;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.integration.support.EchoService;
import ru.ludwigandreas.grpc.server.integration.support.TestConflictException;
import ru.ludwigandreas.grpc.server.test.LudwigGrpcServerTest;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;

/**
 * A failure reaches the caller as the status the platform's problem mapping yields.
 */
@LudwigGrpcServerTest(properties = "ludwig.grpc.server.security.enabled=false")
@ImportAutoConfiguration(ObservabilityCoreAutoConfiguration.class)
class GrpcServerProblemIT {

    private final GrpcStatusCodec codec = new GrpcStatusCodec("test");

    @Autowired
    private ManagedChannel channel;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {EchoService.FAIL_MAPPED, EchoService.FAIL_WRAPPED, EchoService.FAIL_ON_ERROR})
    @DisplayName("a mapped exception - thrown, wrapped or passed to onError - arrives as its mapped code")
    void mappedFailure(String behaviour) {
        StatusRuntimeException failure = catchThrowableOfType(
                () -> Calls.stub(channel).echo(Calls.text(behaviour)), StatusRuntimeException.class);

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        DecodedProblem decoded = codec.decode(failure).orElseThrow();
        assertThat(decoded.fromPlatform()).isTrue();
        assertThat(decoded.problemCode()).contains(TestConflictException.CODE);
        assertThat(decoded.problem().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("an unmapped exception is INTERNAL, carries the correlation id and leaks no message")
    void unmappedFailure() {
        StatusRuntimeException failure = catchThrowableOfType(
                () -> Calls.stub(channel, "x-correlation-id", "corr-unmapped-1")
                        .echo(Calls.text(EchoService.FAIL_UNMAPPED)),
                StatusRuntimeException.class);

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(failure.getStatus().getDescription()).doesNotContain(EchoService.SECRET);
        assertThat(StatusProto.fromThrowable(failure).toString())
                .doesNotContain(EchoService.SECRET)
                .contains("corr-unmapped-1");
        assertThat(codec.decode(failure).orElseThrow().correlationId()).contains("corr-unmapped-1");
    }
}
