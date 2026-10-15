package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.config.GrpcClientAutoConfiguration;

/**
 * Calls are balanced per call over every address the target resolves to.
 */
@ExtendWith(OutputCaptureExtension.class)
class GrpcClientBalancingIT {

    private static final String SCHEME = "ludwigtest";
    private static final List<Server> SERVERS = new ArrayList<>();
    private static final List<AtomicInteger> SERVED = new ArrayList<>();

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcClientAutoConfiguration.class))
            .withPropertyValues("ludwig.grpc.allow-plaintext=true",
                    "ludwig.grpc.client.clients.three.target=" + SCHEME + ":///three",
                    "ludwig.grpc.client.clients.three.plaintext=true");

    @BeforeAll
    static void startThree() throws IOException {
        List<SocketAddress> addresses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            AtomicInteger served = new AtomicInteger();
            Server server = ServerBuilder.forPort(0).addService(new EchoServiceGrpc.EchoServiceImplBase() {
                @Override
                public void echo(EchoRequest request, StreamObserver<EchoReply> observer) {
                    served.incrementAndGet();
                    observer.onNext(EchoReply.newBuilder().setText(request.getText()).build());
                    observer.onCompleted();
                }
            }).build().start();
            SERVERS.add(server);
            SERVED.add(served);
            addresses.add(new InetSocketAddress("127.0.0.1", server.getPort()));
        }
        NameResolverRegistry.getDefaultRegistry().register(new ThreeAddresses(addresses));
    }

    @AfterAll
    static void stopThree() {
        SERVERS.forEach(Server::shutdownNow);
    }

    @Test
    @DisplayName("with default configuration every one of three servers receives calls")
    void roundRobin() {
        RUNNER.run(context -> {
            for (int i = 0; i < 30; i++) {
                Echo.echo(context.getBean(GrpcChannelRegistry.class).channel("three"), "spread");
            }
            assertThat(SERVED).allSatisfy(served -> assertThat(served.get()).isPositive());
        });
    }

    @Test
    @DisplayName("pick_first starts, and is warned about with the client named")
    void pickFirstWarns(CapturedOutput output) {
        RUNNER.withPropertyValues("ludwig.grpc.client.clients.three.load-balancing=pick_first")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).contains("\"three\" uses load-balancing pick_first");
                });
    }

    private static final class ThreeAddresses extends NameResolverProvider {

        private final List<SocketAddress> addresses;

        ThreeAddresses(List<SocketAddress> addresses) {
            this.addresses = addresses;
        }

        @Override
        protected boolean isAvailable() {
            return true;
        }

        @Override
        protected int priority() {
            return 5;
        }

        @Override
        public String getDefaultScheme() {
            return SCHEME;
        }

        @Override
        public NameResolver newNameResolver(URI target, NameResolver.Args args) {
            if (!SCHEME.equals(target.getScheme())) {
                return null;
            }
            return new NameResolver() {
                @Override
                public String getServiceAuthority() {
                    return "three";
                }

                @Override
                public void start(Listener2 listener) {
                    List<EquivalentAddressGroup> groups =
                            addresses.stream().map(EquivalentAddressGroup::new).toList();
                    listener.onResult(ResolutionResult.newBuilder().setAddresses(groups).build());
                }

                @Override
                public void shutdown() {
                }
            };
        }
    }
}
