package ru.ludwigandreas.grpc.server.integration.support;

import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.operation.RequiresOperation;

/**
 * The echo service with operations: {@code Echo} a business read, {@code Store} an administrative change and
 * {@code Append} an administrative privileged call. Each answers with the administrative invocation bound on its own
 * thread - {@code <operation>|<reason>}, which is what its audit events would record - or {@code business} when none
 * is.
 */
@LudwigGrpcService
public class AdminEchoService extends EchoService {

    private final Map<String, AtomicInteger> invoked = new ConcurrentHashMap<>();

    public int invoked(String method) {
        AtomicInteger count = invoked.get(method);
        return count == null ? 0 : count.get();
    }

    @Override
    @RequiresOperation("catalog.item.read")
    public void echo(EchoRequest request, StreamObserver<EchoReply> observer) {
        answer("echo", observer);
    }

    @Override
    @RequiresOperation("catalog.item.store")
    public void store(EchoRequest request, StreamObserver<EchoReply> observer) {
        answer("store", observer);
    }

    @Override
    @RequiresOperation("catalog.item.append")
    public void append(EchoRequest request, StreamObserver<EchoReply> observer) {
        answer("append", observer);
    }

    private void answer(String method, StreamObserver<EchoReply> observer) {
        invoked.computeIfAbsent(method, key -> new AtomicInteger()).incrementAndGet();
        observer.onNext(AdminInvocation.current()
                .map(invocation -> EchoReply.newBuilder()
                        .setText(invocation.operation().id() + "|" + invocation.reason())
                        .setCaller(invocation.principal().subject()).build())
                .orElseGet(() -> EchoReply.newBuilder().setText("business").build()));
        observer.onCompleted();
    }
}
