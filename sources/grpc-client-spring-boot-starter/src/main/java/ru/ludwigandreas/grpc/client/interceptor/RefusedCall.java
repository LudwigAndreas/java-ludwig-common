package ru.ludwigandreas.grpc.client.interceptor;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;

/**
 * A call that is closed with a status as soon as it starts, and sends nothing.
 */
final class RefusedCall<Q, R> extends ClientCall<Q, R> {

    private final Status status;

    RefusedCall(Status status) {
        this.status = status;
    }

    @Override
    public void start(Listener<R> listener, Metadata headers) {
        listener.onClose(status, new Metadata());
    }

    @Override
    public void request(int count) {
    }

    @Override
    public void cancel(String message, Throwable cause) {
    }

    @Override
    public void halfClose() {
    }

    @Override
    public void sendMessage(Q message) {
    }
}
