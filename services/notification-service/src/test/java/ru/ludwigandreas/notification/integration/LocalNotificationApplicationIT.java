package ru.ludwigandreas.notification.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.notification.local.ChatApiStub;
import ru.ludwigandreas.notification.local.IdentityProviderStub;
import ru.ludwigandreas.notification.local.LocalApi;
import ru.ludwigandreas.notification.local.LocalCaller;
import ru.ludwigandreas.notification.local.LocalCallersSummary;
import ru.ludwigandreas.notification.local.RecipientSeed;
import ru.ludwigandreas.notification.local.WebhookReceiverStub;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.testsupport.local.mail.LocalMailbox;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * Starts the local application the way a developer does, and uses it.
 *
 * <p>This test is what keeps the reference example alive. {@code LocalNotificationApplication} is not
 * exercised by anything else in the build, so without this a change could break it and nobody would
 * find out until the next person tried to debug something.
 *
 * <p>No {@code @SpringBootTest}: the application is started through its launcher, on a random port,
 * and called over HTTP from outside the context with tokens from the stand-in issuer - which is how
 * it is used. Nothing here drives the delivery queue by hand either; the poller is on, so the waits
 * below are waits for the service to do its own work.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalNotificationApplicationIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(60);
    private static final Duration SHORT_LIVED = Duration.ofMinutes(5);

    private static ConfigurableApplicationContext application;
    private static String baseUrl;

    @BeforeAll
    static void start() {
        launch();
    }

    @AfterAll
    static void stop() {
        if (application != null) {
            application.close();
        }
    }

    private static void launch() {
        application = LaunchedApplication.start();
        baseUrl = "http://localhost:" + application.getEnvironment().getProperty("local.server.port");
    }

    @Test
    @Order(1)
    @DisplayName("the whole service starts: every channel, both Kafka consumers and the poller on")
    void startsWithNothingSwitchedOff() throws IOException, InterruptedException {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/server/info", null).body()).contains("notification-service");

        assertThat(property("ludwig.notification.channels.chat.enabled")).isEqualTo("true");
        assertThat(property("ludwig.notification.channels.webhook.enabled")).isEqualTo("true");
        assertThat(property("ludwig.notification.ingress.kafka-enabled")).isEqualTo("true");
        assertThat(property("ludwig.identity.kafka.enabled")).isEqualTo("true");
        assertThat(application.containsBean("identityKafkaListenerContainerFactory")).isTrue();
        assertThat(application.getEnvironment()
                .getProperty("ludwig.notification.queue.poller-enabled", "true")).isEqualTo("true");
    }

    @Test
    @Order(2)
    @DisplayName("a token from the stand-in issuer is validated by the production decoder")
    void realTokensAreValidated() throws IOException, InterruptedException {
        String adminToken = issuer().tokenFor(LocalCaller.ADMIN, SHORT_LIVED);
        assertThat(get("/api/v1/notifications/deliveries", adminToken).statusCode()).isEqualTo(200);

        assertThat(get("/api/v1/notifications/deliveries", null).statusCode()).isEqualTo(401);

        String forgedIssuer = new IdentityProviderStub().mintAs("http://127.0.0.1:1",
                LocalCaller.ADMIN.subject(), "Local Admin", LocalCaller.TENANT,
                IdentityProviderStub.AUDIENCE, SHORT_LIVED);
        assertThat(get("/api/v1/notifications/deliveries", forgedIssuer).statusCode())
                .as("a token signed by a key the issuer does not publish")
                .isEqualTo(401);

        // Authenticated, but the directory gives Ada no role: who you are comes from the token, what
        // you may do comes from the projection.
        String adaToken = issuer().tokenFor(LocalCaller.ADA, SHORT_LIVED);
        assertThat(get("/api/v1/notifications/deliveries", adaToken).statusCode()).isEqualTo(403);
    }

    /**
     * The service is configured with {@code ludwig.security.jwt.audiences=notification-service}, so a
     * token the same issuer minted for another service must be refused.
     *
     * <p>It was not, until the security starter learned to install its audience check on the lazy
     * decoder Spring Boot builds from an {@code issuer-uri}. This is that fix seen from outside: a real
     * issuer, a correctly signed token, the wrong audience.
     */
    @Test
    @Order(2)
    @DisplayName("a correctly signed token for another audience is refused")
    void wrongAudienceIsRejected() throws IOException, InterruptedException {
        String wrongAudience = issuer().mint(LocalCaller.ADMIN.subject(), "Local Admin",
                LocalCaller.TENANT, "some-other-service", SHORT_LIVED);

        assertThat(get("/api/v1/notifications/deliveries", wrongAudience).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(3)
    @DisplayName("the command the summary prints for the administrator works as printed")
    void printedCommandWorks() throws IOException, InterruptedException {
        List<String> lines = application.getBean(LocalCallersSummary.class).lines();
        String adminLine = lines.stream()
                .filter(line -> line.startsWith(LocalCaller.ADMIN.subject() + ":"))
                .findFirst().orElseThrow();

        // curl -H 'Authorization: Bearer <token>' <url>
        String token = adminLine.substring(adminLine.indexOf("Bearer ") + "Bearer ".length(),
                adminLine.lastIndexOf('\''));
        String url = adminLine.substring(adminLine.lastIndexOf(' ') + 1);

        assertThat(url).startsWith(baseUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token).GET().build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        assertThat(lines).anyMatch(line -> line.contains("/swagger-ui/index.html"));
    }

    @Test
    @Order(4)
    @DisplayName("the seeded notification reaches Ada's inbox, the mail server and the chat stand-in")
    void seededNotificationIsDelivered() throws IOException, InterruptedException {
        String adaToken = issuer().tokenFor(LocalCaller.ADA, SHORT_LIVED);

        await().atMost(DELIVERED_WITHIN).untilAsserted(() -> {
            assertThat(get("/api/v1/inbox", adaToken).body()).contains("\"templateKey\":\"welcome\"");
            // Announcements are not inbox items; they have an endpoint of their own.
            assertThat(get("/api/v1/announcements", adaToken).body()).contains("platform-release");
        });
        await().atMost(DELIVERED_WITHIN).untilAsserted(() ->
                assertThat(recipientsOf(mailbox().received())).contains(LocalCaller.ADA.email()));
        await().atMost(DELIVERED_WITHIN).untilAsserted(() -> stub(ChatApiStub.NAME).server().verify(
                postRequestedFor(urlPathEqualTo(ChatApiStub.SEND_PATH))
                        .withRequestBody(matchingJsonPath("$.recipient", equalTo(RecipientSeed.ADA_CHAT_HANDLE)))));
    }

    @Test
    @Order(5)
    @DisplayName("the request seeded on the Kafka ingress topic is consumed and delivered")
    void seededIngressRequestIsConsumed() {
        await().atMost(DELIVERED_WITHIN).untilAsserted(() ->
                assertThat(recipientsOf(mailbox().received())).contains(RecipientSeed.EMAIL_ONLY_ADDRESS));
    }

    @Test
    @Order(6)
    @DisplayName("a notification for the webhook stand-in's address arrives there, signed")
    void webhookDeliveryIsSigned() {
        RunningStub webhooks = stub(WebhookReceiverStub.NAME);
        application.getBean(LocalApi.class).post(LocalCaller.ADMIN, "/api/v1/notifications",
                new SendNotificationRequest(WebhookReceiverStub.TEMPLATE_KEY, "orders",
                        CategoryClassDto.TRANSACTIONAL, PriorityDto.NORMAL, Set.of(ChannelTypeDto.WEBHOOK),
                        List.of(new RecipientDto(null, WebhookReceiverStub.endpoint(webhooks), "en", null,
                                Map.of())),
                        Map.of("orderId", "A-1001"), null));

        await().atMost(DELIVERED_WITHIN).untilAsserted(() ->
                assertThat(webhooks.server().findAll(postRequestedFor(urlPathMatching("/hooks/.*")))).hasSize(1));
        LoggedRequest received = webhooks.server().findAll(postRequestedFor(urlPathMatching("/hooks/.*"))).get(0);
        String signatureHeader = property("ludwig.notification.channels.webhook.signature-header") != null
                ? property("ludwig.notification.channels.webhook.signature-header")
                : received.getHeaders().keys().stream()
                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("signature"))
                        .findFirst().orElse("(no signature header)");
        assertThat(received.getHeader(signatureHeader)).isNotBlank();
    }

    @Test
    @Order(7)
    @DisplayName("stopping and starting again leaves the same seed data, with no duplicate")
    void relaunchStartsFromTheSameState() {
        long usersBefore = application.getBean(SecurityUserRepository.class).count();
        application.close();

        launch();

        assertThat(application.getBean(SecurityUserRepository.class).count())
                .isEqualTo(usersBefore)
                .isEqualTo(4);
        assertThat(application.getBean(ObjectMapper.class)).isNotNull();
    }

    private static IdentityProviderStub issuer() {
        return (IdentityProviderStub) stub(IdentityProviderStub.NAME).definition();
    }

    private static RunningStub stub(String name) {
        return application.getBean(StubServers.class).stub(name);
    }

    private static LocalMailbox mailbox() {
        return application.getBean(LocalMailbox.class);
    }

    private static List<String> recipientsOf(List<MimeMessage> messages) throws MessagingException {
        List<String> recipients = new java.util.ArrayList<>();
        for (MimeMessage message : messages) {
            recipients.add(message.getAllRecipients()[0].toString());
        }
        return recipients;
    }

    private static String property(String name) {
        return application.getEnvironment().getProperty(name);
    }

    private static HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
