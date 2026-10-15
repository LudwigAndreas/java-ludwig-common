package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;

import io.grpc.ManagedChannelBuilder;

/** A client assembled by hand: no deadline ceiling, no breaker, no audit, its own retry. */
public class HandBuiltChannel {

    public Object open() {
        return ManagedChannelBuilder.forTarget("dns:///pricing:9090").build();
    }
}
