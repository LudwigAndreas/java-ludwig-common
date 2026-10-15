package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.client.LudwigGrpcClient;
import ru.ludwigandreas.grpc.client.config.GrpcClientAutoConfiguration;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.grpc.server.config.GrpcServerAutoConfiguration;
import ru.ludwigandreas.grpc.server.config.GrpcServerObservabilityAutoConfiguration;
import ru.ludwigandreas.grpc.server.config.GrpcServerSecurityAutoConfiguration;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;
import ru.ludwigandreas.webcore.config.WebCorePreferenceAutoConfiguration;
import ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration;

/**
 * Both starters in one application: a call this service serves makes a call to another service,
 * and the downstream call carries the caller's token, a deadline no later than the inbound one and
 * the inbound correlation id.
 *
 * <p>The server authenticates callers into the platform's own {@code LudwigAuthentication}, which
 * keeps no token - the case in which reading the relay token from the security context finds none.
 */
@SpringBootTest(classes = GrpcServerToClientIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "ludwig.grpc.server.in-process-name=" + GrpcServerToClientIT.FRONT,
            "ludwig.grpc.client.clients.downstream.target=in-process:" + GrpcServerToClientIT.DOWNSTREAM,
            "ludwig.grpc.client.clients.downstream.auth.type=oauth2-token-relay",
            "ludwig.grpc.client.clients.downstream.auth.relay-enabled=true"})
@ImportAutoConfiguration({
    WebCoreProblemAutoConfiguration.class, WebCorePreferenceAutoConfiguration.class, AuditCoreAutoConfiguration.class,
    ObservabilityCoreAutoConfiguration.class, GrpcServerAutoConfiguration.class,
    GrpcServerSecurityAutoConfiguration.class, GrpcServerObservabilityAutoConfiguration.class,
    GrpcClientAutoConfiguration.class})
class GrpcServerToClientIT {

    static final String FRONT = "e2e-front";
    static final String DOWNSTREAM = "e2e-downstream";
    private static final Metadata.Key<String> CORRELATION =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private static ScriptedServer downstream;
    private static ManagedChannel front;

    @BeforeAll
    static void start() {
        downstream = ScriptedServer.start(DOWNSTREAM);
        front = InProcessChannelBuilder.forName(FRONT).build();
    }

    @AfterAll
    static void stop() {
        front.shutdownNow();
        downstream.close();
    }

    @Test
    @DisplayName("a call made while serving one relays the caller's token, its deadline and its correlation id")
    void relayFromAHandler() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer valid-alice");
        headers.put(CORRELATION, "corr-e2e");

        EchoReply reply = EchoServiceGrpc.newBlockingStub(front)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(2, TimeUnit.SECONDS)
                .echo(EchoRequest.newBuilder().setText("relay-me").build());

        assertThat(reply.getText()).isEqualTo("relay-me");
        ScriptedServer.Arrival arrival = downstream.arrivals("relay-me").get(0);
        assertThat(arrival.authorization()).isEqualTo("Bearer valid-alice");
        assertThat(arrival.deadlineMillis()).isBetween(1L, 2_000L);
        assertThat(arrival.headers().get(CORRELATION)).isEqualTo("corr-e2e");
    }

    @Configuration(proxyBeanMethods = false)
    static class Application {

        @Bean
        FrontService frontService(@LudwigGrpcClient("downstream") EchoServiceGrpc.EchoServiceBlockingStub next) {
            return new FrontService(next);
        }

        /** Tokens are {@code valid-<subject>}; anything else is rejected. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!token.startsWith("valid-")) {
                    throw new BadJwtException("not a test token");
                }
                return Jwt.withTokenValue(token).header("alg", "none").subject(token.substring(6)).build();
            };
        }

        /** The platform's own principal type, which keeps no token. */
        @Bean
        Converter<Jwt, AbstractAuthenticationToken> jwtConverter() {
            return jwt -> TestPrincipalBuilder.user(jwt.getSubject()).authentication();
        }
    }

    /** Serves a call by making one. */
    @LudwigGrpcService
    static class FrontService extends EchoServiceGrpc.EchoServiceImplBase {

        private final EchoServiceGrpc.EchoServiceBlockingStub next;

        FrontService(EchoServiceGrpc.EchoServiceBlockingStub next) {
            this.next = next;
        }

        @Override
        public void echo(EchoRequest request, StreamObserver<EchoReply> observer) {
            observer.onNext(next.echo(request));
            observer.onCompleted();
        }
    }
}
