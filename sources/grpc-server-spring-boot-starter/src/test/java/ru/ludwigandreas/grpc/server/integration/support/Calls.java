package ru.ludwigandreas.grpc.server.integration.support;

import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;

/**
 * Stub and request shorthand for the integration tests.
 */
public final class Calls {

    private Calls() {
    }

    /**
     * A blocking stub that sends the given metadata, as alternating names and values.
     */
    public static EchoServiceGrpc.EchoServiceBlockingStub stub(Channel channel, String... metadata) {
        return EchoServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(metadata)));
    }

    public static Metadata metadata(String... pairs) {
        Metadata metadata = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.put(Metadata.Key.of(pairs[i], Metadata.ASCII_STRING_MARSHALLER), pairs[i + 1]);
        }
        return metadata;
    }

    public static EchoRequest text(String text) {
        return EchoRequest.newBuilder().setText(text).build();
    }
}
