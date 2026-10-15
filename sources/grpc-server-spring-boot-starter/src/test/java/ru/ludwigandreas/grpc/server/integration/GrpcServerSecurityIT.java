package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.integration.support.EchoService;
import ru.ludwigandreas.grpc.server.integration.support.TestTokens;
import ru.ludwigandreas.grpc.server.spi.LudwigGrpcServerInterceptor;
import ru.ludwigandreas.grpc.server.test.LudwigGrpcServerTest;

/**
 * Deny by default, every exemption explicit, and no server without a way to authenticate.
 */
class GrpcServerSecurityIT {

    private static final String PERMITTED = "ludwig.echo.v1.EchoService/Store";

    @Nested
    @LudwigGrpcServerTest(properties = "ludwig.grpc.server.security.permit-unauthenticated=" + PERMITTED)
    @Import({TestTokens.class, Authenticated.SeenHeaders.class})
    @ExtendWith(OutputCaptureExtension.class)
    class Authenticated {

        @Autowired
        private ManagedChannel channel;

        @Autowired
        private EchoService service;

        @Autowired
        private AtomicReference<Metadata> seen;

        @Test
        @DisplayName("no credential is UNAUTHENTICATED and the handler does not run")
        void noCredential() {
            StatusRuntimeException refused = catchThrowableOfType(
                    () -> Calls.stub(channel).echo(Calls.text("anonymous")), StatusRuntimeException.class);

            assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
            assertThat(service.invocations("anonymous")).isZero();
        }

        @Test
        @DisplayName("a rejected token is UNAUTHENTICATED and the handler does not run")
        void rejectedToken() {
            StatusRuntimeException refused = catchThrowableOfType(
                    () -> Calls.stub(channel, "authorization", "Bearer forged").echo(Calls.text("forged")),
                    StatusRuntimeException.class);

            assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
            assertThat(service.invocations("forged")).isZero();
        }

        @Test
        @DisplayName("a valid token reaches the handler as the platform's principal")
        void validToken() {
            EchoReply reply = Calls.stub(channel, "authorization", TestTokens.bearer("alice")).echo(Calls.text("hi"));

            assertThat(reply.getCaller()).isEqualTo("alice");
        }

        @Test
        @DisplayName("the health service answers an anonymous caller")
        void healthIsOpen() {
            HealthCheckResponse response =
                    HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance());

            assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
        }

        @Test
        @DisplayName("a permitted method is served anonymously, with no principal, and was warned about")
        void permittedMethod(CapturedOutput output) {
            EchoReply reply = Calls.stub(channel).store(Calls.text("public"));

            assertThat(reply.getCaller()).isEmpty();
            assertThat(output).contains("permit-unauthenticated").contains(PERMITTED);
        }

        @Test
        @DisplayName("identity metadata a caller could forge never reaches the handler")
        void forgedIdentityIsStripped() {
            Calls.stub(channel, "authorization", TestTokens.bearer("alice"),
                    "x-ludwig-subject", "admin", "x-forwarded-user", "admin", "x-kept", "yes")
                    .echo(Calls.text("strip"));

            assertThat(seen.get().keys()).contains("x-kept").doesNotContain("x-ludwig-subject", "x-forwarded-user");
        }

        @TestConfiguration(proxyBeanMethods = false)
        static class SeenHeaders {

            @Bean
            AtomicReference<Metadata> seen() {
                return new AtomicReference<>();
            }

            @Bean
            LudwigGrpcServerInterceptor recordingInterceptor(AtomicReference<Metadata> seen) {
                return new LudwigGrpcServerInterceptor() {
                    @Override
                    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                                       ServerCallHandler<Q, R> next) {
                        seen.set(headers);
                        return next.startCall(call, headers);
                    }
                };
            }
        }
    }

    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    class Startup {

        @Test
        @DisplayName("a service with no JwtDecoder refuses to start and names the opt-out property")
        void noDecoderFailsStartup() {
            GrpcServerLifecycleIT.RUNNER.withUserConfiguration(EchoApplication.class)
                    .withPropertyValues("ludwig.grpc.server.security.enabled=true")
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("ludwig.grpc.server.security.enabled"));
        }

        @Test
        @DisplayName("security.enabled=false starts, and says the server is unauthenticated")
        void explicitOptOutStarts(CapturedOutput output) {
            GrpcServerLifecycleIT.RUNNER.withUserConfiguration(EchoApplication.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(output).contains("WITHOUT authentication");
            });
        }
    }
}
