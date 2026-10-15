package ru.ludwigandreas.grpc.client.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.function.Supplier;

/**
 * Sends the current correlation id, so the peer's logs and this service's join on it.
 */
public final class CorrelationInterceptor implements ClientInterceptor {

    private final Metadata.Key<String> key;
    private final Supplier<String> correlationId;

    /**
     * Creates the interceptor.
     *
     * @param key           the metadata key, the correlation header lower-cased
     * @param correlationId the current id, or {@code null}
     */
    public CorrelationInterceptor(Metadata.Key<String> key, Supplier<String> correlationId) {
        this.key = key;
        this.correlationId = correlationId;
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        String id = correlationId.get();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                if (id != null && !headers.containsKey(key)) {
                    headers.put(key, id);
                }
                super.start(listener, headers);
            }
        };
    }
}
