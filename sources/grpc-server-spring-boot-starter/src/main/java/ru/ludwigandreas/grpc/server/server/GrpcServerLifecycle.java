package ru.ludwigandreas.grpc.server.server;

import io.grpc.BindableService;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.TlsServerCredentials;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.StringUtils;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.grpc.server.error.GrpcServerConfigurationException;
import ru.ludwigandreas.grpc.server.error.GrpcServerMessages;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;

/**
 * The gRPC server, as a Spring lifecycle. The one class on this side that builds a transport.
 *
 * <p><b>Start.</b> With no bean carrying {@link LudwigGrpcService} nothing is validated and nothing
 * is bound: a starter on the classpath is not a reason to open a port. Otherwise the configuration
 * is validated, every service is bound behind the fixed interceptor chain, and the health service
 * is registered beside them.
 *
 * <p><b>Health.</b> {@code grpc.health.v1.Health} follows Spring's {@link ReadinessState}, for the
 * server as a whole and for each service by name, so a Kubernetes gRPC probe and the HTTP readiness
 * endpoint cannot disagree.
 *
 * <p><b>Stop.</b> Health turns {@code NOT_SERVING} first, so a balancer that probes stops routing
 * before the listener closes. Then the server stops accepting, calls in flight get
 * {@code shutdown-grace}, and whatever is left is cancelled.
 *
 * <p>The default {@link SmartLifecycle} phase is kept: last to start, first to stop, so the server
 * never accepts a call before the beans behind it are up or after they have begun to close.
 */
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle, ApplicationListener<AvailabilityChangeEvent<?>> {

    private final GrpcServerProperties properties;
    private final ListableBeanFactory beans;
    private final List<ServerInterceptor> chain;
    private final boolean authenticationAvailable;
    private final GrpcServerConfigurationValidator validator;
    private final ObjectProvider<SslBundles> sslBundles;
    private final ObjectProvider<ApplicationAvailability> availability;
    private final HealthStatusManager health = new HealthStatusManager();
    private final List<String> serviceNames = new ArrayList<>();

    private volatile Server server;
    private BoundedCallExecutor executor;

    /**
     * Creates the lifecycle.
     *
     * @param properties   the server configuration
     * @param beans        where the stereotyped services are found
     * @param chain        the interceptor chain, outermost first
     * @param authenticationAvailable whether the chain authenticates callers
     * @param validator    refuses an unsafe configuration
     * @param sslBundles   Spring Boot's SSL bundles, when there are any
     * @param availability the application's availability state, when it is published
     */
    public GrpcServerLifecycle(GrpcServerProperties properties, ListableBeanFactory beans,
                               List<ServerInterceptor> chain, boolean authenticationAvailable,
                               GrpcServerConfigurationValidator validator,
                               ObjectProvider<SslBundles> sslBundles,
                               ObjectProvider<ApplicationAvailability> availability) {
        this.properties = properties;
        this.beans = beans;
        this.chain = List.copyOf(chain);
        this.authenticationAvailable = authenticationAvailable;
        this.validator = validator;
        this.sslBundles = sslBundles;
        this.availability = availability;
    }

    @Override
    public void start() {
        List<ServerServiceDefinition> definitions = definitions();
        if (definitions.isEmpty()) {
            log.debug("No @LudwigGrpcService bean; the gRPC server is not started");
            return;
        }
        validator.validate(authenticationAvailable);

        GrpcServerProperties.Executor pool = properties.getExecutor();
        executor = new BoundedCallExecutor(pool.getThreads(), pool.getQueueCapacity());
        ServerBuilder<?> builder = transport().executor(executor);
        for (ServerServiceDefinition definition : definitions) {
            // interceptForward: the first interceptor in the list is the first to see the call.
            builder.addService(ServerInterceptors.interceptForward(definition, chain));
            serviceNames.add(definition.getServiceDescriptor().getName());
        }
        builder.addService(ServerInterceptors.interceptForward(health.getHealthService(), chain));
        if (properties.getReflection().isEnabled()) {
            builder.addService(ServerInterceptors.interceptForward(ProtoReflectionServiceV1.newInstance(), chain));
        }
        ApplicationAvailability current = availability.getIfAvailable();
        serving(current == null || current.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC);
        try {
            server = builder.build().start();
        } catch (IOException bindFailed) {
            executor.close();
            throw new UncheckedIOException("The gRPC server could not bind", bindFailed);
        }
        log.info("gRPC server started on {} serving {}",
                StringUtils.hasText(properties.getInProcessName())
                        ? "in-process name '" + properties.getInProcessName() + "'"
                        : "port " + server.getPort(),
                serviceNames);
    }

    @Override
    public void stop() {
        Server running = server;
        if (running == null) {
            return;
        }
        health.enterTerminalState();
        running.shutdown();
        try {
            if (!running.awaitTermination(properties.getShutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("gRPC calls still in flight after {}; cancelling them", properties.getShutdownGrace());
                running.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor.close();
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    @Override
    public void onApplicationEvent(AvailabilityChangeEvent<?> event) {
        if (server != null && event.getState() instanceof ReadinessState readiness) {
            serving(readiness == ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    /**
     * The port the server is listening on - the real one when {@code port} was {@code 0}.
     *
     * @return the port, or {@code -1} when the server is not running or is in-process
     */
    public int port() {
        Server running = server;
        return running == null ? -1 : running.getPort();
    }

    private void serving(boolean ready) {
        ServingStatus status = ready ? ServingStatus.SERVING : ServingStatus.NOT_SERVING;
        health.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, status);
        serviceNames.forEach(name -> health.setStatus(name, status));
    }

    private List<ServerServiceDefinition> definitions() {
        List<ServerServiceDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, Object> bean : beans.getBeansWithAnnotation(LudwigGrpcService.class).entrySet()) {
            if (!(bean.getValue() instanceof BindableService service)) {
                throw new GrpcServerConfigurationException(GrpcServerMessages.startup(
                        "not-a-service", bean.getKey(), bean.getValue().getClass().getName()));
            }
            definitions.add(service.bindService());
        }
        return definitions;
    }

    private ServerBuilder<?> transport() {
        if (StringUtils.hasText(properties.getInProcessName())) {
            return InProcess.builder(properties.getInProcessName());
        }
        InetSocketAddress address = StringUtils.hasText(properties.getAddress())
                ? new InetSocketAddress(properties.getAddress(), properties.getPort())
                : new InetSocketAddress(properties.getPort());
        GrpcServerProperties.Keepalive keepalive = properties.getKeepalive();
        return NettyServerBuilder.forAddress(address, credentials())
                .maxInboundMessageSize(Math.toIntExact(properties.getMaxInboundMessageSize().toBytes()))
                .maxInboundMetadataSize(Math.toIntExact(properties.getMaxInboundMetadataSize().toBytes()))
                .maxConcurrentCallsPerConnection(properties.getMaxConcurrentCallsPerConnection())
                .maxConnectionAge(properties.getMaxConnectionAge().toMillis(), TimeUnit.MILLISECONDS)
                .maxConnectionAgeGrace(properties.getMaxConnectionAgeGrace().toMillis(), TimeUnit.MILLISECONDS)
                .handshakeTimeout(properties.getHandshakeTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .permitKeepAliveTime(keepalive.getPermitTime().toMillis(), TimeUnit.MILLISECONDS)
                .permitKeepAliveWithoutCalls(keepalive.isPermitWithoutCalls());
    }

    private ServerCredentials credentials() {
        GrpcServerProperties.Tls tls = properties.getTls();
        if (!StringUtils.hasText(tls.getBundle())) {
            // The validator has already decided this plaintext listener is permitted.
            return InsecureServerCredentials.create();
        }
        SslManagerBundle managers = sslBundles.getObject().getBundle(tls.getBundle()).getManagers();
        TlsServerCredentials.Builder credentials =
                TlsServerCredentials.newBuilder().keyManager(managers.getKeyManagers());
        if (tls.getClientAuth() != GrpcServerProperties.ClientAuth.NONE) {
            credentials.trustManager(managers.getTrustManagers())
                    .clientAuth(tls.getClientAuth() == GrpcServerProperties.ClientAuth.REQUIRE
                            ? TlsServerCredentials.ClientAuth.REQUIRE
                            : TlsServerCredentials.ClientAuth.OPTIONAL);
        }
        return credentials.build();
    }

    /**
     * Kept apart so the in-process transport, an optional dependency, is linked only when asked for.
     */
    private static final class InProcess {

        private InProcess() {
        }

        static ServerBuilder<?> builder(String name) {
            return InProcessServerBuilder.forName(name);
        }
    }
}
