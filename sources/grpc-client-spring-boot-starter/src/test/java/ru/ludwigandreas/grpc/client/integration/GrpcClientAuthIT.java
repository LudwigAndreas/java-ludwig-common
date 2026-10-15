package ru.ludwigandreas.grpc.client.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.config.GrpcClientIntegrations;
import ru.ludwigandreas.grpc.client.error.GrpcClientAuthenticationException;
import ru.ludwigandreas.grpc.client.error.GrpcClientException;
import ru.ludwigandreas.grpc.client.integration.support.ScriptedServer;
import ru.ludwigandreas.grpc.client.test.LudwigGrpcClientTest;
import ru.ludwigandreas.outboundtoken.CachedToken;

/**
 * Machine tokens shared and renewed once, a rejected token re-minted once, relay fails closed.
 */
@LudwigGrpcClientTest(properties = {
    "ludwig.grpc.client.clients.machine.target=in-process:" + GrpcClientAuthIT.PEER,
    "ludwig.grpc.client.clients.machine.auth.type=oauth2-client-credentials",
    "ludwig.grpc.client.clients.machine.auth.registration-id=machine",
    "ludwig.grpc.client.clients.rejected.target=in-process:" + GrpcClientAuthIT.PEER,
    "ludwig.grpc.client.clients.rejected.auth.type=oauth2-client-credentials",
    "ludwig.grpc.client.clients.rejected.auth.registration-id=rejected",
    "ludwig.grpc.client.clients.relay.target=in-process:" + GrpcClientAuthIT.PEER,
    "ludwig.grpc.client.clients.relay.auth.type=oauth2-token-relay",
    "ludwig.grpc.client.clients.relay.auth.relay-enabled=true"})
@Import(GrpcClientAuthIT.Tokens.class)
class GrpcClientAuthIT {

    static final String PEER = "auth-peer";
    private static final ScriptedServer SERVER = ScriptedServer.start(PEER);

    @Autowired
    private GrpcChannelRegistry registry;

    @Autowired
    private Tokens tokens;

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("200 concurrent calls at the moment a machine token expires mint exactly one new token")
    void singleFlight() throws Exception {
        Echo.echo(registry.channel("machine"), "warm-up");
        assertThat(tokens.minted("machine")).isEqualTo(1);
        Thread.sleep(1_200);

        ExecutorService callers = Executors.newFixedThreadPool(32);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                calls.add(callers.submit((Callable<Object>) () -> Echo.echo(registry.channel("machine"), "burst")));
            }
            for (Future<?> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
        } finally {
            callers.shutdownNow();
        }

        assertThat(tokens.minted("machine")).isEqualTo(2);
        assertThat(SERVER.arrivals("burst")).allSatisfy(arrival ->
                assertThat(arrival.authorization()).isEqualTo("Bearer machine-2"));
    }

    @Test
    @DisplayName("a rejected token is minted again once, then the call succeeds; a second rejection is reported")
    void rejectedToken() {
        SERVER.fail("reject-once", Status.UNAUTHENTICATED);

        Echo.echo(registry.channel("rejected"), "reject-once");

        assertThat(SERVER.arrivals("reject-once")).extracting(ScriptedServer.Arrival::authorization)
                .containsExactly("Bearer rejected-1", "Bearer rejected-2");

        SERVER.fail("reject-twice", Status.UNAUTHENTICATED, Status.UNAUTHENTICATED);
        StatusRuntimeException failure = Echo.failure(() -> Echo.echo(registry.channel("rejected"), "reject-twice"));

        assertThat(SERVER.arrivals("reject-twice")).hasSize(2);
        assertThat(GrpcClientException.from(failure)).isInstanceOf(GrpcClientAuthenticationException.class);
    }

    @Test
    @DisplayName("relay outside an authenticated request fails and sends nothing anonymously")
    void relayWithoutInboundToken() {
        StatusRuntimeException failure = Echo.failure(() -> Echo.echo(registry.channel("relay"), "anonymous"));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(GrpcClientException.from(failure)).isInstanceOf(GrpcClientAuthenticationException.class);
        assertThat(SERVER.arrivals("anonymous")).isEmpty();
    }

    @Test
    @DisplayName("relay inside an authenticated request sends the caller's own token")
    void relayWithInboundToken() {
        Jwt jwt = Jwt.withTokenValue("inbound-token").header("alg", "none").subject("alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        Echo.echo(registry.channel("relay"), "relayed");

        assertThat(SERVER.arrivals("relayed").get(0).authorization()).isEqualTo("Bearer inbound-token");
    }

    /**
     * Mints {@code <client>-<n>}: the first token of each client expires 31s out (usable for one
     * second under the 30s skew), every later one in an hour.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Tokens {

        private final java.util.Map<String, AtomicInteger> mints = new java.util.concurrent.ConcurrentHashMap<>();

        int minted(String client) {
            AtomicInteger count = mints.get(client);
            return count == null ? 0 : count.get();
        }

        @Bean
        GrpcClientIntegrations.ClientCredentials clientCredentials() {
            return new GrpcClientIntegrations.ClientCredentials((client, auth) -> () -> {
                int n = mints.computeIfAbsent(client, key -> new AtomicInteger()).incrementAndGet();
                Instant expires = n == 1 ? Instant.now().plusSeconds(31) : Instant.now().plusSeconds(3_600);
                return new CachedToken(client + "-" + n, expires);
            });
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("not used: this service only relays");
            };
        }
    }
}
