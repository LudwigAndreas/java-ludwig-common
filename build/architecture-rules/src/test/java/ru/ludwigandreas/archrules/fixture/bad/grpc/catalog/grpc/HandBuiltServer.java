package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;

import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

/** A server assembled by hand, through the shaded builder: unauthenticated unless somebody remembered. */
public class HandBuiltServer {

    private static final int PORT = 9090;

    public Object start() {
        return NettyServerBuilder.forPort(PORT).build();
    }
}
