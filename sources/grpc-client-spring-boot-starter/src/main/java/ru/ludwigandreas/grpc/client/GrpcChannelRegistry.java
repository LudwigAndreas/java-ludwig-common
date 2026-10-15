package ru.ludwigandreas.grpc.client;

import io.grpc.Channel;
import java.util.Set;

/**
 * The configured clients' channels, by name - the third way to consume a client, beside an injected
 * stub and an injected channel. All three hand out the same intercepted channel, so a call made any
 * of the three ways carries the same deadline, resilience, credentials, metrics, log and audit.
 */
public interface GrpcChannelRegistry {

    /**
     * A client's channel.
     *
     * @param clientName a name under {@code ludwig.grpc.client.clients}
     * @return the intercepted channel
     * @throws IllegalArgumentException naming the configured clients, when {@code clientName} is not one
     */
    Channel channel(String clientName);

    /**
     * The configured client names.
     *
     * @return the names
     */
    Set<String> names();
}
