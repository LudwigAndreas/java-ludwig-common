package ru.ludwigandreas.grpc.server.integration.support;

import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminInvocation;

/**
 * The echo service with administrative methods, one per tier. Each answers with the invocation bound on its own
 * thread - {@code <operation>|<reason>} - which is what its audit events would record.
 */
@LudwigGrpcService
public class AdminEchoService extends EchoService {

    private final Map<String, AtomicInteger> invoked = new ConcurrentHashMap<>();

    public int invoked(String method) {
        AtomicInteger count = invoked.get(method);
        return count == null ? 0 : count.get();
    }

    @Override
    @AdminAccess("catalog.read")
    public void echo(EchoRequest request, StreamObserver<EchoReply> observer) {
        answer("echo", observer);
    }

    @Override
    @AdminAccess("catalog.operate")
    public void store(EchoRequest request, StreamObserver<EchoReply> observer) {
        answer("store", observer);
    }

    @Override
    @AdminAccess("catalog.privileged")
    public void append(EchoRequest request, StreamObserver<EchoReply> observer) {
        answer("append", observer);
    }

    private void answer(String method, StreamObserver<EchoReply> observer) {
        invoked.computeIfAbsent(method, key -> new AtomicInteger()).incrementAndGet();
        AdminInvocation invocation = AdminInvocation.require();
        observer.onNext(EchoReply.newBuilder()
                .setText(invocation.operation().id() + "|" + invocation.reason())
                .setCaller(invocation.principal().subject()).build());
        observer.onCompleted();
    }
}
