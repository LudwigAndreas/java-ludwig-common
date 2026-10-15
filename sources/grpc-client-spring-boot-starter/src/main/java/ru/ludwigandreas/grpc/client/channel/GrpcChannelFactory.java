package ru.ludwigandreas.grpc.client.channel;

import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.util.StringUtils;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;

/**
 * Builds one client's channel. The one class on this side that builds a transport, which is what a
 * later move onto Spring gRPC's channel factory replaces.
 *
 * <p>One channel per client, never shared: two clients to the same host get two channels, so one's
 * saturation, credentials and breaker cannot reach the other.
 */
public final class GrpcChannelFactory {

    /** A target of this form connects in-process, for tests. */
    public static final String IN_PROCESS = "in-process:";

    private GrpcChannelFactory() {
    }

    /**
     * Builds the channel.
     *
     * @param settings      the client's resolved settings, already validated
     * @param serviceConfig the retry service config
     * @param sslBundles    Spring Boot's SSL bundles, or {@code null}
     * @return the channel, not yet connected
     */
    public static ManagedChannel create(ClientSettings settings, Map<String, ?> serviceConfig, SslBundles sslBundles) {
        String target = settings.getTarget();
        ManagedChannelBuilder<?> builder;
        if (target.startsWith(IN_PROCESS)) {
            builder = InProcess.builder(target.substring(IN_PROCESS.length()));
        } else {
            ClientSettings.Keepalive keepalive = settings.getKeepalive();
            builder = NettyChannelBuilder.forTarget(target, credentials(settings, sslBundles))
                    .keepAliveTime(keepalive.getTime().toMillis(), TimeUnit.MILLISECONDS)
                    .keepAliveTimeout(keepalive.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .keepAliveWithoutCalls(keepalive.getWithoutCalls())
                    .idleTimeout(settings.getIdleTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    // In-process has one direct address and refuses a policy; there is nothing to balance.
                    .defaultLoadBalancingPolicy(settings.getLoadBalancing());
        }
        builder.maxInboundMessageSize(Math.toIntExact(settings.getMaxInboundMessageSize().toBytes()))
                // The retry policy is the contract's; a DNS TXT record must not replace it.
                .disableServiceConfigLookUp()
                .defaultServiceConfig(serviceConfig);
        if (Boolean.TRUE.equals(settings.getRetry().getEnabled())) {
            builder.enableRetry();
        } else {
            builder.disableRetry();
        }
        return builder.build();
    }

    private static ChannelCredentials credentials(ClientSettings settings, SslBundles sslBundles) {
        if (Boolean.TRUE.equals(settings.getPlaintext())) {
            return InsecureChannelCredentials.create();
        }
        String bundle = settings.getTls().getBundle();
        if (!StringUtils.hasText(bundle) || sslBundles == null) {
            return TlsChannelCredentials.create();
        }
        SslManagerBundle managers = sslBundles.getBundle(bundle).getManagers();
        return TlsChannelCredentials.newBuilder()
                .trustManager(managers.getTrustManagers())
                .keyManager(managers.getKeyManagers())
                .build();
    }

    /** Kept apart so the in-process transport, an optional dependency, is linked only when asked for. */
    private static final class InProcess {

        private InProcess() {
        }

        static ManagedChannelBuilder<?> builder(String name) {
            return InProcessChannelBuilder.forName(name);
        }
    }
}
