package io.grpc.netty.shaded.io.grpc.netty;

/**
 * Test stub of the builder as {@code grpc-netty-shaded} relocates it. The rule has to know this name
 * as well as the plain one, because the shaded artifact is the one this platform ships.
 */
public abstract class NettyServerBuilder {

    public static NettyServerBuilder forPort(int port) {
        throw new UnsupportedOperationException(Integer.toString(port));
    }

    public abstract Object build();
}
