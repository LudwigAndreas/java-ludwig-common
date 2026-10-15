package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Context;

/**
 * What an outer stage needs to learn from an inner one about the same call.
 *
 * <p>The exception translator sits outside the context bridge, so by the time it holds a failure
 * the bridge's thread bindings have been undone - and a problem rendered then would be in the
 * JVM's locale rather than the caller's. The bridge therefore leaves the context it built here, and
 * the translator renders inside it.
 */
final class CallState {

    static final Context.Key<CallState> KEY = Context.key("ludwig-grpc-call-state");

    private volatile Context bridged;

    Context bridged() {
        return bridged;
    }

    void bridged(Context context) {
        this.bridged = context;
    }
}
