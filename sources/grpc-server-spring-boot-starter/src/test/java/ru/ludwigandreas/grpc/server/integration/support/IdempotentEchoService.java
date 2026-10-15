package ru.ludwigandreas.grpc.server.integration.support;

import io.grpc.stub.StreamObserver;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.idempotency.api.Idempotent;

/**
 * The echo service with two methods opted in to replay: one that takes a key when offered, one that
 * demands it.
 */
public class IdempotentEchoService extends EchoService {

    @Override
    @Idempotent
    public void store(EchoRequest request, StreamObserver<EchoReply> observer) {
        super.store(request, observer);
    }

    @Override
    @Idempotent(required = true)
    public void append(EchoRequest request, StreamObserver<EchoReply> observer) {
        super.append(request, observer);
    }
}
