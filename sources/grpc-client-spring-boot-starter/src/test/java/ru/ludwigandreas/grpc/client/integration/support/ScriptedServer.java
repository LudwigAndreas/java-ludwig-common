package ru.ludwigandreas.grpc.client.integration.support;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;

/**
 * An in-process peer the tests script: per request text, a queue of failures to answer before
 * succeeding, an optional pause, and a record of what every call arrived with.
 */
public final class ScriptedServer implements AutoCloseable {

    public static final Metadata.Key<String> PUSHBACK =
            Metadata.Key.of("grpc-retry-pushback-ms", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Server server;
    private final Map<String, Deque<Failure>> scripts = new ConcurrentHashMap<>();
    private final Map<String, Long> pauses = new ConcurrentHashMap<>();
    private final List<Arrival> arrivals = new CopyOnWriteArrayList<>();

    /**
     * What a call arrived with.
     */
    public record Arrival(String method, String text, String authorization, Long deadlineMillis,
                          Metadata headers, long atNanos) {
    }

    /**
     * One scripted failure.
     */
    public record Failure(Status status, Metadata trailers) {
    }

    private ScriptedServer(String name) throws IOException {
        Context.Key<Arrival> arrivalKey = Context.key("arrival");
        ServerInterceptor recorder = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                               ServerCallHandler<Q, R> next) {
                Deadline deadline = Context.current().getDeadline();
                Arrival partial = new Arrival(call.getMethodDescriptor().getBareMethodName(), null,
                        headers.get(AUTHORIZATION),
                        deadline == null ? null : deadline.timeRemaining(TimeUnit.MILLISECONDS), headers,
                        System.nanoTime());
                return Contexts.interceptCall(Context.current().withValue(arrivalKey, partial), call, headers, next);
            }
        };
        EchoServiceGrpc.EchoServiceImplBase service = new EchoServiceGrpc.EchoServiceImplBase() {
            @Override
            public void echo(EchoRequest request, StreamObserver<EchoReply> observer) {
                answer(request, observer);
            }

            @Override
            public void store(EchoRequest request, StreamObserver<EchoReply> observer) {
                answer(request, observer);
            }

            @Override
            public void append(EchoRequest request, StreamObserver<EchoReply> observer) {
                answer(request, observer);
            }

            private void answer(EchoRequest request, StreamObserver<EchoReply> observer) {
                Arrival partial = arrivalKey.get();
                arrivals.add(new Arrival(partial.method(), request.getText(), partial.authorization(),
                        partial.deadlineMillis(), partial.headers(), partial.atNanos()));
                Long pause = pauses.get(request.getText());
                if (pause != null) {
                    try {
                        Thread.sleep(pause);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                Deque<Failure> script = scripts.get(request.getText());
                Failure failure = script == null ? null : script.poll();
                if (failure != null) {
                    observer.onError(failure.status().asRuntimeException(failure.trailers()));
                    return;
                }
                observer.onNext(EchoReply.newBuilder().setText(request.getText())
                        .setCaller(partial.authorization() == null ? "" : partial.authorization()).build());
                observer.onCompleted();
            }
        };
        this.server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(service, recorder))
                .build()
                .start();
    }

    public static ScriptedServer start(String name) {
        try {
            return new ScriptedServer(name);
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** Fails the next calls with this text, in order, then succeeds. */
    public ScriptedServer fail(String text, Status... statuses) {
        Deque<Failure> script = scripts.computeIfAbsent(text, key -> new ConcurrentLinkedDeque<>());
        for (Status status : statuses) {
            script.add(new Failure(status, new Metadata()));
        }
        return this;
    }

    /** Fails the next call with this text, with trailers. */
    public ScriptedServer fail(String text, Status status, Metadata trailers) {
        scripts.computeIfAbsent(text, key -> new ConcurrentLinkedDeque<>()).add(new Failure(status, trailers));
        return this;
    }

    /** Fails every call with this text. */
    public ScriptedServer alwaysFail(String text, Status status, int times) {
        for (int i = 0; i < times; i++) {
            fail(text, status);
        }
        return this;
    }

    public ScriptedServer pause(String text, long millis) {
        pauses.put(text, millis);
        return this;
    }

    public List<Arrival> arrivals(String text) {
        return arrivals.stream().filter(arrival -> text.equals(arrival.text())).toList();
    }

    public void reset() {
        scripts.clear();
        pauses.clear();
        arrivals.clear();
    }

    @Override
    public void close() {
        server.shutdownNow();
    }
}
