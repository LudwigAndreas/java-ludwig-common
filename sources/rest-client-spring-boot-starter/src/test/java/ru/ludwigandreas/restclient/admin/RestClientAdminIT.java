package ru.ludwigandreas.restclient.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.control.ControlStore;
import ru.ludwigandreas.job.core.control.ReplicaControlPoller;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.restclient.admin.service.RestClientAdminService;
import ru.ludwigandreas.restclient.admin.service.RestClientBreakerControl;
import ru.ludwigandreas.restclient.admin.service.RestClientBreakersControl;
import ru.ludwigandreas.restclient.admin.service.RestClientTokenResetControl;
import ru.ludwigandreas.restclient.auth.provider.ClientTokenSources;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.resilience.ResilienceRegistries;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code restclient} area over job-core's real channel: a client-credentials client {@code partner} and a
 * plain one, against a MockWebServer that is both the partner and its token endpoint. Replica A is this context;
 * replica B, a second poller with breakers of its own, is there to show breaker state is per replica.
 */
@SpringBootTest(classes = RestClientAdminIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.jwt.enabled=false",
            "ludwig.web.i18n.configure-validator=false",
            "spring.liquibase.change-log=classpath:db/changelog/job-core/job-core-changelog.xml",
            "ludwig.job-core.owner=replica-a",
            "ludwig.job-core.control.poll-interval=PT1H",
            "ludwig.rest-client.clients.partner.auth.type=oauth2-client-credentials",
            "ludwig.rest-client.clients.partner.auth.registration-id=partner",
            "ludwig.rest-client.clients.partner.resilience.enabled=true",
            "ludwig.rest-client.clients.partner.resilience.retry.enabled=false",
            "ludwig.rest-client.clients.partner.resilience.circuit-breaker.enabled=true",
            "ludwig.rest-client.clients.plain.auth.type=none",
            "ludwig.security.roles.OPS.operations[replica.instance.read]=ALL",
            "ludwig.security.roles.OPS.operations[replica.command.read]=ALL",
            "ludwig.security.roles.OPS.operations[restclient.client.read]=ALL",
            "ludwig.security.roles.OPS.operations[restclient.breaker.read]=ALL",
            "ludwig.security.roles.OPS.operations[restclient.token.reset]=ALL",
            "ludwig.security.roles.PARTNER_ADMIN.operations[restclient.breaker.set]=ALL"})
@AutoConfigureMockMvc
class RestClientAdminIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "s3cret-client-secret";
    private static final AtomicInteger MINTED = new AtomicInteger();
    private static final AtomicInteger PARTNER_CALLS = new AtomicInteger();
    private static final List<String> SEEN_TOKENS = new CopyOnWriteArrayList<>();
    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();
    private static final MockWebServer SERVER = server();

    private static MockWebServer server() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().startsWith("/token")) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                            "{\"access_token\":\"token-" + MINTED.incrementAndGet()
                                    + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
                }
                PARTNER_CALLS.incrementAndGet();
                SEEN_TOKENS.add(String.valueOf(request.getHeader("Authorization")));
                return new MockResponse().setBody("ok");
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return server;
    }

    @DynamicPropertySource
    static void baseUrls(DynamicPropertyRegistry registry) {
        registry.add("ludwig.rest-client.clients.partner.base-url", () -> SERVER.url("/").toString());
        registry.add("ludwig.rest-client.clients.plain.base-url", () -> SERVER.url("/").toString());
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.shutdown();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        @Bean
        DataSource dataSource() {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(Containers.postgres().getJdbcUrl());
            source.setUser(Containers.postgres().getUsername());
            source.setPassword(Containers.postgres().getPassword());
            return source;
        }

        @Bean
        ClientRegistrationRepository clientRegistrations() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("partner")
                    .clientId("catalog").clientSecret(SECRET)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .tokenUri(SERVER.url("/token").toString()).build());
        }

        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ReplicaControlPoller replicaA;

    @Autowired
    private RestClientRegistry clients;

    @Autowired
    private ResilienceRegistries registries;

    @Autowired
    private RestClientAdminService service;

    private ReplicaControlPoller replicaB;
    private ThreadPoolTaskScheduler schedulerB;
    private CircuitBreakerRegistry breakersB;

    @BeforeEach
    void twoReplicas() {
        AUDIT.clear();
        new JdbcTemplate(dataSource).execute("DELETE FROM job_control_command; DELETE FROM job_instance");
        registries.circuitBreakers().getAllCircuitBreakers().forEach(breaker -> breaker.transitionToClosedState());
        breakersB = CircuitBreakerRegistry.ofDefaults();
        breakersB.circuitBreaker("partner");
        ResilienceRegistries registriesB = new ResilienceRegistries(breakersB, BulkheadRegistry.ofDefaults(),
                ThreadPoolBulkheadRegistry.ofDefaults(), RateLimiterRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults());
        schedulerB = new ThreadPoolTaskScheduler();
        schedulerB.initialize();
        replicaB = new ReplicaControlPoller(schedulerB, new ControlStore(dataSource), "replica-b",
                Map.of(RestClientBreakersControl.NAME, new RestClientBreakersControl(registriesB),
                        RestClientBreakerControl.NAME, new RestClientBreakerControl(service, registriesB),
                        RestClientTokenResetControl.NAME,
                        new RestClientTokenResetControl(service, new ClientTokenSources())), JSON,
                new ReplicaControlPoller.ControlSettings(Duration.ofHours(1), Duration.ofSeconds(5),
                        Duration.ofDays(7)), new JdbcRunLock(dataSource, "replica-b"));
        replicaB.runNow();
        replicaA.runNow();
    }

    @AfterEach
    void stopReplicaB() {
        schedulerB.shutdown();
    }

    private static RequestPostProcessor ops() {
        return TestPrincipalBuilder.user("olga").roles("OPS").postProcessor();
    }

    private static RequestPostProcessor partnerAdmin() {
        return TestPrincipalBuilder.user("pat").roles("OPS", "PARTNER_ADMIN").postProcessor();
    }

    private MvcResult submit(String control, Object body, RequestPostProcessor caller, String reason)
            throws Exception {
        var request = post("/admin/v1/replicas/controls/" + control).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)).with(caller);
        return mvc.perform(reason == null ? request : request.header(OperationEvaluator.REASON_HEADER, reason))
                .andReturn();
    }

    private JsonNode run(MvcResult submitted) throws Exception {
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        replicaA.runNow();
        replicaB.runNow();
        return JSON.readTree(mvc.perform(get(submitted.getResponse().getHeader("Location")).with(ops()))
                .andReturn().getResponse().getContentAsString());
    }

    private void callPartner() {
        clients.rest("partner").get().uri("/orders").retrieve().toBodilessEntity();
    }

    @Test
    @DisplayName("each named client is listed once with host, auth kind and resilience, and no secret or token")
    void listsClients() throws Exception {
        callPartner();

        String body = mvc.perform(get("/admin/v1/restclient/clients").with(ops())).andReturn().getResponse()
                .getContentAsString();

        JsonNode listed = JSON.readTree(body);
        assertThat(listed.findValuesAsText("name")).containsExactly("partner", "plain");
        assertThat(listed.get(0).path("authKind").asText()).isEqualTo("oauth2-client-credentials");
        assertThat(listed.get(0).path("circuitBreaker").path("enabled").asBoolean()).isTrue();
        assertThat(body).doesNotContain(SECRET).doesNotContain("token-");
    }

    @Test
    @DisplayName("a token reset makes the next call mint a new token; a client without client credentials is a 400")
    void resetsTheToken() throws Exception {
        callPartner();
        int minted = MINTED.get();

        JsonNode command = run(submit("restclienttokenreset", Map.of("client", "partner"), ops(), null));
        callPartner();

        assertThat(command.path("operationEnvelope").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(MINTED.get()).isEqualTo(minted + 1);
        assertThat(SEEN_TOKENS.get(SEEN_TOKENS.size() - 1)).isEqualTo("Bearer token-" + (minted + 1));
        assertThat(submit("restclienttokenreset", Map.of("client", "plain"), ops(), null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(submit("restclienttokenreset", Map.of("client", "nosuch"), ops(), null).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("reading the breakers shows each replica's own state, including the one replica that is OPEN")
    void readsBreakersPerReplica() throws Exception {
        callPartner();
        breakersB.circuitBreaker("partner").transitionToOpenState();

        JsonNode command = run(submit("restclientbreakers", Map.of(), ops(), null));

        JsonNode targets = command.path("targets");
        assertThat(targets.findValuesAsText("owner")).containsExactly("replica-a", "replica-b");
        assertThat(targets.get(0).path("result").path("breakers").findValuesAsText("state")).containsExactly("CLOSED");
        assertThat(targets.get(1).path("result").path("breakers").findValuesAsText("state")).containsExactly("OPEN");
    }

    @Test
    @DisplayName("forcing a breaker open is privileged and needs a reason; then calls fail without reaching the "
            + "partner, and the audit records the reason")
    void forcesABreakerOpen() throws Exception {
        callPartner();
        Map<String, String> forceOpen = Map.of("client", "partner", "state", "FORCED_OPEN");

        assertThat(submit("restclientbreaker", forceOpen, ops(), "INC-9").getResponse().getStatus()).isEqualTo(403);
        assertThat(submit("restclientbreaker", forceOpen, partnerAdmin(), null).getResponse().getStatus())
                .isEqualTo(400);
        JsonNode command = run(submit("restclientbreaker", forceOpen, partnerAdmin(), "INC-9"));
        int before = PARTNER_CALLS.get();
        Throwable refused = catchThrowable(this::callPartner);

        assertThat(command.path("targets").get(0).path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(refused).isNotNull();
        assertThat(PARTNER_CALLS.get()).isEqualTo(before);
        assertThat(AUDIT).filteredOn(event -> "replica-command".equals(event.resource().type())).singleElement()
                .satisfies(event -> assertThat(event.attributes()).containsEntry("reason", "INC-9"));

        run(submit("restclientbreaker", Map.of("client", "partner", "state", "CLOSED"), partnerAdmin(), "INC-9"));
        callPartner();
        assertThat(PARTNER_CALLS.get()).isEqualTo(before + 1);
    }
}
