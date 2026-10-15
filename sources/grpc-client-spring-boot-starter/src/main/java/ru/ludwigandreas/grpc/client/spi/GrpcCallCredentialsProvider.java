package ru.ludwigandreas.grpc.client.spi;

import io.grpc.CallCredentials;
import java.util.Map;

/**
 * An authentication type a service adds. Publish a bean and set {@code auth.type} to its
 * {@link #type()}; {@code auth.properties} reaches {@link #create} unchanged.
 *
 * <p>The credentials are applied per attempt, on gRPC's executor, so an implementation may block
 * to fetch a token but must not read the caller's thread-locals - they are not there.
 */
public interface GrpcCallCredentialsProvider {

    /**
     * The {@code auth.type} this provider answers to.
     *
     * @return the type
     */
    String type();

    /**
     * The credentials for one client.
     *
     * @param clientName the client
     * @param properties the client's {@code auth.properties}
     * @return the credentials
     */
    CallCredentials create(String clientName, Map<String, String> properties);
}
