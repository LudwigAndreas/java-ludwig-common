package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.core.problem.DecodedProblem;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.integration.support.EchoService;
import ru.ludwigandreas.grpc.server.integration.support.IdempotentEchoService;
import ru.ludwigandreas.idempotency.error.IdempotencyProblemCodes;
import ru.ludwigandreas.testsupport.junit.LudwigPostgresTest;

/**
 * An opted-in unary method runs once per key, against the idempotency module's real Postgres store
 * and its real claim statement - no table, statement or SQL of this starter's own.
 */
@LudwigPostgresTest
@TestPropertySource(properties = {
    "ludwig.grpc.server.in-process-name=" + GrpcServerIdempotencyIT.SERVER,
    "ludwig.grpc.server.security.enabled=false",
    "ludwig.security.enabled=false",
    "spring.liquibase.enabled=false"})
class GrpcServerIdempotencyIT {

    static final String SERVER = "grpc-idempotency-it";

    private static ManagedChannel channel;

    private final GrpcStatusCodec codec = new GrpcStatusCodec("test");

    @Autowired
    private EchoService service;

    @BeforeAll
    static void openChannel() {
        channel = InProcessChannelBuilder.forName(SERVER).build();
    }

    @AfterAll
    static void closeChannel() {
        channel.shutdownNow();
    }

    private static EchoServiceGrpc.EchoServiceBlockingStub keyed(String key, AtomicReference<Metadata> headers) {
        return Calls.stub(channel, "idempotency-key", key)
                .withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(headers, new AtomicReference<>()));
    }

    @Test
    @DisplayName("a repeat with the same key and request gets the original reply, marked, and the handler ran once")
    void replays() {
        String key = UUID.randomUUID().toString();
        AtomicReference<Metadata> first = new AtomicReference<>();
        AtomicReference<Metadata> second = new AtomicReference<>();

        EchoReply original = keyed(key, first).store(Calls.text("replay-me"));
        EchoReply repeated = keyed(key, second).store(Calls.text("replay-me"));

        assertThat(repeated).isEqualTo(original);
        assertThat(service.invocations("replay-me")).isEqualTo(1);
        assertThat(first.get().get(GrpcMetadataKeys.IDEMPOTENCY_REPLAYED)).isNull();
        assertThat(second.get().get(GrpcMetadataKeys.IDEMPOTENCY_REPLAYED)).isEqualTo("true");
    }

    @Test
    @DisplayName("a key reused with a different request is refused and the handler does not run")
    void keyReuse() {
        String key = UUID.randomUUID().toString();
        keyed(key, new AtomicReference<>()).store(Calls.text("first-request"));

        StatusRuntimeException refused = catchThrowableOfType(
                () -> keyed(key, new AtomicReference<>()).store(Calls.text("other-request")),
                StatusRuntimeException.class);

        DecodedProblem problem = codec.decode(refused).orElseThrow();
        assertThat(problem.problemCode()).contains(IdempotencyProblemCodes.FINGERPRINT_MISMATCH);
        assertThat(service.invocations("other-request")).isZero();
    }

    @Test
    @DisplayName("a duplicate that arrives while the first is running is told to retry, and does not run")
    void concurrentDuplicate() throws Exception {
        String key = UUID.randomUUID().toString();
        service.arm();
        ListenableFuture<EchoReply> running = EchoServiceGrpc.newFutureStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(Calls.metadata("idempotency-key", key)))
                .store(Calls.text(EchoService.BLOCK));
        assertThat(service.awaitEntered()).isTrue();

        StatusRuntimeException refused = catchThrowableOfType(
                () -> keyed(key, new AtomicReference<>()).store(Calls.text(EchoService.BLOCK)),
                StatusRuntimeException.class);

        DecodedProblem problem = codec.decode(refused).orElseThrow();
        assertThat(problem.problemCode()).contains(IdempotencyProblemCodes.IN_PROGRESS);
        assertThat(problem.retryAfter()).isPresent();

        service.release();
        assertThat(running.get(5, TimeUnit.SECONDS).getText()).isEqualTo(EchoService.BLOCK);
        assertThat(service.invocations(EchoService.BLOCK)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed call is not stored: the retry with the same key runs the handler again")
    void failureReleasesTheKey() {
        String key = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < 2; attempt++) {
            StatusRuntimeException failed = catchThrowableOfType(
                    () -> keyed(key, new AtomicReference<>()).store(Calls.text(EchoService.FAIL_MAPPED)),
                    StatusRuntimeException.class);
            assertThat(failed.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        }

        assertThat(service.invocations(EchoService.FAIL_MAPPED)).isEqualTo(2);
    }

    @Test
    @DisplayName("a method that requires a key answers INVALID_ARGUMENT without one")
    void requiredKeyMissing() {
        StatusRuntimeException refused = catchThrowableOfType(
                () -> Calls.stub(channel).append(Calls.text("no-key")), StatusRuntimeException.class);

        assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(service.invocations("no-key")).isZero();
    }

    @Test
    @DisplayName("a method that is not opted in is untouched by a key")
    void notOptedIn() {
        String key = UUID.randomUUID().toString();

        keyed(key, new AtomicReference<>()).echo(Calls.text("plain"));
        keyed(key, new AtomicReference<>()).echo(Calls.text("plain"));

        assertThat(service.invocations("plain")).isEqualTo(2);
    }

    @Test
    @DisplayName("an opted-in method with no idempotency store fails startup and names the method")
    void noStoreFailsStartup() {
        GrpcServerLifecycleIT.RUNNER
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        ru.ludwigandreas.grpc.server.config.GrpcServerIdempotencyAutoConfiguration.class))
                .withBean(IdempotentEchoService.class)
                .run(context -> assertThat(org.springframework.core.NestedExceptionUtils
                        .getMostSpecificCause(context.getStartupFailure()))
                        .hasMessageContaining("ludwig.echo.v1.EchoService/Store")
                        .hasMessageContaining("IdempotencyStore"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {

        @Bean
        EchoService echoService() {
            return new IdempotentEchoService();
        }

        @Bean
        ru.ludwigandreas.webcore.problem.ExceptionProblemMapper testConflictMapper() {
            return new EchoApplication().testConflictMapper();
        }
    }
}
