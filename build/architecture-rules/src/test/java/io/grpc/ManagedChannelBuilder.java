package io.grpc;

/** Test stub of the real builder. */
public abstract class ManagedChannelBuilder {

    public static ManagedChannelBuilder forTarget(String target) {
        throw new UnsupportedOperationException(target);
    }

    public abstract Object build();
}
