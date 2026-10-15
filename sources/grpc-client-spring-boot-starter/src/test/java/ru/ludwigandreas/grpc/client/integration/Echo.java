package ru.ludwigandreas.grpc.client.integration;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;

/**
 * Request shorthand.
 */
final class Echo {

    private Echo() {
    }

    static EchoRequest text(String text) {
        return EchoRequest.newBuilder().setText(text).build();
    }

    static EchoServiceGrpc.EchoServiceBlockingStub on(Channel channel) {
        return EchoServiceGrpc.newBlockingStub(channel);
    }

    static EchoReply echo(Channel channel, String text) {
        return on(channel).echo(text(text));
    }

    static StatusRuntimeException failure(Runnable call) {
        return Assertions.catchThrowableOfType(call::run, StatusRuntimeException.class);
    }
}
