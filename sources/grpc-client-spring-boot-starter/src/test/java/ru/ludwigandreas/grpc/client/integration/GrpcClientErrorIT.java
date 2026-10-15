package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.error.GrpcClientConnectionException;
import ru.ludwigandreas.grpc.client.error.GrpcClientException;
import ru.ludwigandreas.grpc.client.error.GrpcClientResponseException;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Typed failures, the decoded problem, and what one escaping a controller is rendered as - through
 * the same registry the HTTP advice uses, which walks to the typed cause.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.clients.echo.target=in-process:" + GrpcClientErrorIT.PEER,
    "ludwig.grpc.client.clients.echo.retry.enabled=false"})
class GrpcClientErrorIT {

    static final String PEER = "error-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private ProblemMapperRegistry problems;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    private StatusRuntimeException failWith(String text, Status status) {
        SERVER.fail(text, status);
        return Echo.failure(() -> Echo.echo(registry.channel("echo"), text));
    }

    private ProblemDefinition rendered(StatusRuntimeException failure) {
        return problems.resolve(failure).orElseThrow();
    }

    @Test
    @DisplayName("a platform problem from the peer arrives with its gRPC code and its problem code")
    void platformProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(409);
        problem.setProperty(GrpcStatusCodec.CODE, "peer.error.conflict");
        StatusRuntimeException answer = new GrpcStatusCodec("peer").toException(problem, Locale.ENGLISH, "c-1", null);
        SERVER.fail("problem", answer.getStatus(), answer.getTrailers());

        StatusRuntimeException failure = Echo.failure(() -> Echo.echo(registry.channel("echo"), "problem"));

        assertThat(GrpcClientException.from(failure)).isInstanceOfSatisfying(GrpcClientResponseException.class,
                response -> {
                    assertThat(response.peerCode()).isEqualTo(Status.Code.ABORTED);
                    assertThat(response.problem().problemCode()).contains("peer.error.conflict");
                    assertThat(response.clientName()).isEqualTo("echo");
                });
    }

    @Test
    @DisplayName("UNAVAILABLE escaping a controller is upstream-unavailable, 503")
    void unavailable() {
        StatusRuntimeException failure = failWith("down", Status.UNAVAILABLE);

        assertThat(GrpcClientException.from(failure)).isInstanceOf(GrpcClientConnectionException.class);
        assertThat(rendered(failure).status()).isEqualTo(ProblemStatus.SERVICE_UNAVAILABLE);
        assertThat(rendered(failure).code()).isEqualTo(ProblemCodes.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("INVALID_ARGUMENT escaping a controller is a 500, not a 400: this service sent it")
    void callerSide() {
        assertThat(rendered(failWith("invalid", Status.INVALID_ARGUMENT)).status()).isEqualTo(ProblemStatus.INTERNAL);
    }

    @Test
    @DisplayName("RESOURCE_EXHAUSTED escaping a controller is a 429")
    void exhausted() {
        assertThat(rendered(failWith("busy", Status.RESOURCE_EXHAUSTED)).status())
                .isEqualTo(ProblemStatus.TOO_MANY_REQUESTS);
    }
}
