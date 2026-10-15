package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.integration.support.TestTokens;
import ru.ludwigandreas.grpc.server.test.LudwigGrpcServerTest;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The handler sees its own caller, on whichever thread runs it, and nothing of the previous one.
 *
 * <p>Deliberately run on several handler threads and asserted from what the handler itself
 * observed: an implementation that binds the thread-locals once, in {@code interceptCall}, passes
 * any single-threaded version of this test.
 */
@LudwigGrpcServerTest(properties = {
    "ludwig.grpc.server.executor.threads=4",
    // The platform negotiates against its supported locales; "en" alone is the default.
    "ludwig.web.i18n.supported-locales=en,ru",
    "ludwig.grpc.server.security.permit-unauthenticated=ludwig.echo.v1.EchoService/Store"})
@ImportAutoConfiguration(ObservabilityCoreAutoConfiguration.class)
@Import(TestTokens.class)
class GrpcServerContextIT {

    private static final int ROUNDS = 150;

    @Autowired
    private ManagedChannel channel;

    @Autowired
    private UserPreferences ludwigConfiguredPreferences;

    @Test
    @DisplayName("principal, correlation id, locale and zone are the caller's inside the handler")
    void handlerSeesItsCaller() {
        EchoReply reply = Calls.stub(channel,
                "authorization", TestTokens.bearer("alice"), "x-correlation-id", "corr-alice",
                "accept-language", "ru", "x-time-zone", "Europe/Moscow").echo(Calls.text("one"));

        assertThat(reply.getCaller()).isEqualTo("alice");
        assertThat(reply.getCorrelationId()).isEqualTo("corr-alice");
        assertThat(reply.getLocale()).isEqualTo("ru");
        assertThat(reply.getZone()).isEqualTo("Europe/Moscow");
        assertThat(reply.getThread()).startsWith("ludwig-grpc-");
    }

    @Test
    @DisplayName("a caller that sends no locale gets the platform's remaining preference sources")
    void noLocaleFallsBack() {
        EchoReply reply = Calls.stub(channel, "authorization", TestTokens.bearer("alice")).echo(Calls.text("two"));

        assertThat(reply.getLocale()).isEqualTo(ludwigConfiguredPreferences.locale().toLanguageTag());
        assertThat(reply.getZone()).isEqualTo(ludwigConfiguredPreferences.zone().getId());
    }

    @Test
    @DisplayName("a thread reused by another caller - or by an anonymous one - carries nothing over")
    void threadsDoNotLeakBetweenCallers() throws Exception {
        Map<String, Set<String>> callersByThread = new ConcurrentHashMap<>();
        ExecutorService clients = Executors.newFixedThreadPool(8);
        try {
            List<Future<Void>> calls = new ArrayList<>();
            for (int round = 0; round < ROUNDS; round++) {
                calls.add(clients.submit(as("alice", "ru", "Europe/Moscow", callersByThread)));
                calls.add(clients.submit(as("bob", "en", "America/Chicago", callersByThread)));
                calls.add(clients.submit(anonymous(callersByThread)));
            }
            for (Future<Void> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
        } finally {
            clients.shutdownNow();
        }
        // The premise of the test: at least one handler thread really did serve different callers.
        assertThat(callersByThread.values()).anySatisfy(callers -> assertThat(callers).hasSizeGreaterThan(1));
    }

    private Callable<Void> as(String caller, String locale, String zone, Map<String, Set<String>> callersByThread) {
        return () -> {
            EchoReply reply = Calls.stub(channel,
                    "authorization", TestTokens.bearer(caller), "x-correlation-id", "corr-" + caller,
                    "accept-language", locale, "x-time-zone", zone).echo(Calls.text(caller));
            assertThat(reply.getCaller()).isEqualTo(caller);
            assertThat(reply.getCorrelationId()).isEqualTo("corr-" + caller);
            assertThat(reply.getLocale()).isEqualTo(locale);
            assertThat(reply.getZone()).isEqualTo(zone);
            record(callersByThread, reply, caller);
            return null;
        };
    }

    private Callable<Void> anonymous(Map<String, Set<String>> callersByThread) {
        return () -> {
            EchoReply reply = Calls.stub(channel, "x-correlation-id", "corr-anonymous").store(Calls.text("anon"));
            assertThat(reply.getCaller()).isEmpty();
            assertThat(reply.getCorrelationId()).isEqualTo("corr-anonymous");
            assertThat(reply.getLocale()).isEqualTo(ludwigConfiguredPreferences.locale().toLanguageTag());
            record(callersByThread, reply, "");
            return null;
        };
    }

    private static void record(Map<String, Set<String>> callersByThread, EchoReply reply, String caller) {
        callersByThread.computeIfAbsent(reply.getThread(), thread -> ConcurrentHashMap.newKeySet()).add(caller);
        assertThat(new HashSet<>(List.of("alice", "bob", ""))).contains(caller);
    }
}
