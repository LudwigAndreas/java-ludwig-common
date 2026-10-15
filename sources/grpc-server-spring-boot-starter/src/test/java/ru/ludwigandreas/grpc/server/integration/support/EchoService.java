package ru.ludwigandreas.grpc.server.integration.support;

import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The service every integration test serves. It answers with what it observed on its own thread,
 * and the request text selects how it misbehaves.
 */
@LudwigGrpcService
public class EchoService extends EchoServiceGrpc.EchoServiceImplBase {

    public static final String FAIL_MAPPED = "fail:mapped";
    public static final String FAIL_WRAPPED = "fail:wrapped";
    public static final String FAIL_UNMAPPED = "fail:unmapped";
    public static final String FAIL_ON_ERROR = "fail:onError";
    public static final String BLOCK = "block";
    public static final String SECRET = "hunter2";

    private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private volatile CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);

    /** Arms the {@link #BLOCK} behaviour for one more blocked call. */
    public void arm() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    public boolean awaitEntered() throws InterruptedException {
        return entered.await(10, TimeUnit.SECONDS);
    }

    public void release() {
        release.countDown();
    }

    public int invocations(String text) {
        AtomicInteger count = invocations.get(text);
        return count == null ? 0 : count.get();
    }

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

    @Override
    public void stream(EchoRequest request, StreamObserver<EchoReply> observer) {
        for (int i = 0; i < request.getRepeat(); i++) {
            observer.onNext(observed(request.getText(), i + 1));
        }
        observer.onCompleted();
    }

    private void answer(EchoRequest request, StreamObserver<EchoReply> observer) {
        String text = request.getText();
        int invocation = invocations.computeIfAbsent(text, key -> new AtomicInteger()).incrementAndGet();
        switch (text) {
            case FAIL_MAPPED -> throw new TestConflictException();
            case FAIL_WRAPPED -> throw new IllegalStateException("wrapper", new TestConflictException());
            case FAIL_UNMAPPED -> throw new IllegalArgumentException("password=" + SECRET);
            case FAIL_ON_ERROR -> {
                observer.onError(new TestConflictException());
                return;
            }
            case BLOCK -> block();
            default -> {
            }
        }
        observer.onNext(observed(text, invocation));
        observer.onCompleted();
    }

    private void block() {
        entered.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static EchoReply observed(String text, int invocation) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String correlationId = MDC.get("correlationId");
        UserPreferences preferences = UserPreferences.current();
        return EchoReply.newBuilder()
                .setText(text)
                .setCaller(authentication == null ? "" : authentication.getName())
                .setCorrelationId(correlationId == null ? "" : correlationId)
                .setLocale(preferences.locale().toLanguageTag())
                .setZone(preferences.zone().getId())
                .setThread(Thread.currentThread().getName())
                .setInvocation(invocation)
                .build();
    }
}
