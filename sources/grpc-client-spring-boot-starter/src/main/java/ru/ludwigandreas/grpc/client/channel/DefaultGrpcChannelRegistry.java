package ru.ludwigandreas.grpc.client.channel;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;

/**
 * The registry over every configured client, closing their channels gracefully with the context.
 */
public final class DefaultGrpcChannelRegistry implements GrpcChannelRegistry, DisposableBean {

    private static final long CLOSE_SECONDS = 5;

    private final Map<String, ClientRuntime> clients;

    /**
     * Creates the registry.
     *
     * @param clients the clients by name
     */
    public DefaultGrpcChannelRegistry(Map<String, ClientRuntime> clients) {
        this.clients = Map.copyOf(clients);
    }

    @Override
    public Channel channel(String clientName) {
        ClientRuntime client = clients.get(clientName);
        if (client == null) {
            throw new IllegalArgumentException("No gRPC client '" + clientName + "' is configured under "
                    + "ludwig.grpc.client.clients; the configured clients are " + names());
        }
        return client.channel();
    }

    @Override
    public Set<String> names() {
        return new java.util.TreeSet<>(clients.keySet());
    }

    /**
     * Closes every channel: in-flight calls get a moment to finish, then the rest are cancelled.
     */
    @Override
    public void destroy() throws InterruptedException {
        clients.values().forEach(client -> client.managed().shutdown());
        for (ClientRuntime client : clients.values()) {
            ManagedChannel channel = client.managed();
            if (!channel.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }
}
