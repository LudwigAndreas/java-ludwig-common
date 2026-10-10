package ru.ludwigandreas.testsupport.local.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.restclient.error.RestClientConnectionException;
import ru.ludwigandreas.restclient.error.RestClientException;
import ru.ludwigandreas.restclient.error.RestClientResponseException;
import ru.ludwigandreas.restclient.error.RestClientTimeoutException;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApi;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApiStub;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubAddress;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;
import ru.ludwigandreas.testsupport.local.stub.StubFault;
import ru.ludwigandreas.testsupport.local.stub.StubServers;
import ru.ludwigandreas.testsupport.local.stub.WithPartnerStubs;

/**
 * Proves each fault reaches the service through its real client, and that the three ways of setting
 * one are the same switch.
 *
 * <p>The sample client has a 400 ms read timeout and two attempts, so "slow" is observable as a
 * timeout in under a second and "the retry policy ran" as a second recorded request.
 */
@SampleContext
@WithPartnerStubs(SampleStubs.class)
@TestPropertySource(properties = {
        "ludwig.local.stubs.partner.slow-delay=1500ms",
        // Every test here makes the partner fail on purpose and they share one context, so a live
        // circuit breaker opens part-way through the class and the remaining tests are refused by it
        // instead of reaching the stub. The breaker is the starter's to test, not this module's.
        "ludwig.rest-client.clients.partner.resilience.circuit-breaker.enabled=false"
})
class StubFaultIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private PartnerApi partner;

    @Autowired
    private StubServers stubs;

    @Test
    @DisplayName("unavailable: the client sees a 503 and retries it")
    void unavailable() {
        RunningStub stub = stubs.stub("partner");
        stub.fail(StubFault.UNAVAILABLE);

        assertThatThrownBy(() -> partner.thing("1"))
                .isInstanceOf(RestClientResponseException.class)
                .hasMessageContaining("503");
        stub.server().verify(2, anyRequestedFor(anyUrl()));
        assertThat(stub.activeFault()).contains(StubFault.UNAVAILABLE);
    }

    @Test
    @DisplayName("slow: a delay beyond the read timeout is a timeout through the client's translation")
    void slow() {
        RunningStub stub = stubs.stub("partner");
        stub.fail(StubFault.SLOW);

        assertThatThrownBy(() -> partner.thing("1")).isInstanceOf(RestClientTimeoutException.class);
        // The second request is the retry policy running against the timeout. The stub records a
        // request once it has finished answering it, and it is still sitting out its delay when the
        // client has already given up - so the count is awaited, not read at once.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> stub.server().verify(2, anyRequestedFor(anyUrl())));
    }

    @Test
    @DisplayName("reset: the connection is cut and the client reports a connection failure")
    void reset() {
        stubs.stub("partner").fail(StubFault.RESET);

        assertThatThrownBy(() -> partner.thing("1")).isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("empty: the connection closes with no response")
    void empty() {
        stubs.stub("partner").fail(StubFault.EMPTY);

        assertThatThrownBy(() -> partner.thing("1")).isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("refused: nothing listens, and healing brings the stub back on the same port")
    void refusedAndHealed() {
        RunningStub stub = stubs.stub("partner");
        int port = stub.port();
        stub.fail(StubFault.REFUSED);

        assertThatThrownBy(() -> partner.thing("1")).isInstanceOf(RestClientConnectionException.class);
        assertThat(stub.activeFault()).contains(StubFault.REFUSED);

        stub.heal();

        assertThat(stub.port()).isEqualTo(port);
        assertThat(partner.thing("1")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
    }

    @Test
    @DisplayName("a refused connection cannot be scoped to a path")
    void refusedCannotBeScoped() {
        assertThatThrownBy(() -> stubs.stub("partner").fail(StubFault.REFUSED, "/things/.*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a fault scoped to a path leaves the rest of the stub healthy")
    void scopedToAPath() {
        stubs.stub("partner").fail(StubFault.UNAVAILABLE, "/slow-things/.*");

        assertThatThrownBy(() -> partner.slowThing("1")).isInstanceOf(RestClientResponseException.class);
        assertThat(partner.thing("1")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
    }

    @Test
    @DisplayName("clearing a fault restores the healthy answer on the next request")
    void cleared() {
        RunningStub stub = stubs.stub("partner");
        stub.fail(StubFault.UNAVAILABLE);
        stub.fail(StubFault.UNAVAILABLE, "/slow-things/.*");

        stub.heal();

        assertThat(partner.thing("1")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
        assertThat(partner.slowThing("1")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
        assertThat(stub.activeFault()).isEmpty();
    }

    @Test
    @DisplayName("one stub's fault does not touch another stub")
    void oneStubDownTheOtherHealthy() throws IOException, InterruptedException {
        StubCatalog two = () -> List.of(new NamedStub("alpha"), new NamedStub("beta"));
        try (StubServers started = StubServers.start(two, new MockEnvironment())) {
            started.stub("alpha").fail(StubFault.UNAVAILABLE);

            assertThat(status(started.stub("alpha").baseUrl() + "/ping")).isEqualTo(503);
            assertThat(status(started.stub("beta").baseUrl() + "/ping")).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a stub addressed by a property, or by nothing, fails and resets like any other")
    void faultsApplyToEveryKindOfStub() throws IOException, InterruptedException {
        StubCatalog two = () -> List.of(
                new AddressedStub("by-property", StubAddress.property("sample.other.base-url")),
                new AddressedStub("by-nothing", StubAddress.none()));
        try (StubServers started = StubServers.start(two, new MockEnvironment())) {
            started.stub("by-property").fail(StubFault.UNAVAILABLE);
            started.stub("by-nothing").fail(StubFault.UNAVAILABLE);

            assertThat(status(started.stub("by-property").baseUrl() + "/ping")).isEqualTo(503);
            assertThat(status(started.stub("by-nothing").baseUrl() + "/ping")).isEqualTo(503);

            started.resetAll();

            assertThat(status(started.stub("by-property").baseUrl() + "/ping")).isEqualTo(200);
            assertThat(status(started.stub("by-nothing").baseUrl() + "/ping")).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a fault set by the start-up property is in force for the first call")
    void setAtStartUp() throws IOException, InterruptedException {
        StubCatalog one = () -> List.of(new NamedStub("alpha"));
        MockEnvironment configured = new MockEnvironment()
                .withProperty("ludwig.local.stubs.alpha.fault", "unavailable");
        try (StubServers started = StubServers.start(one, configured)) {
            assertThat(status(started.stub("alpha").baseUrl() + "/ping")).isEqualTo(503);
            assertThat(started.stub("alpha").activeFault()).contains(StubFault.UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("a fault set over the admin URL is the same switch the Java API uses")
    void setOverHttp() throws IOException, InterruptedException {
        RunningStub stub = stubs.stub("partner");

        assertThat(putScenarioState(stub, "unavailable")).isEqualTo(200);

        assertThatThrownBy(() -> partner.thing("1")).isInstanceOf(RestClientResponseException.class);
        assertThat(stub.activeFault()).contains(StubFault.UNAVAILABLE);

        // A PUT with no state is WireMock's reset of one scenario, which is the healthy state.
        assertThat(putScenarioState(stub, null)).isEqualTo(200);

        assertThat(partner.thing("1")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
        assertThat(stub.activeFault()).isEmpty();
    }

    @Test
    @DisplayName("an unknown fault name fails start-up listing the valid names")
    void unknownFaultName() {
        StubCatalog one = () -> List.of(new NamedStub("alpha"));
        MockEnvironment configured = new MockEnvironment()
                .withProperty("ludwig.local.stubs.alpha.fault", "exploded");

        assertThatThrownBy(() -> StubServers.start(one, configured))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exploded")
                .hasMessageContaining("unavailable, slow, reset, empty, refused");
    }

    private static int putScenarioState(RunningStub stub, String state) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create(stub.adminUrl() + "/scenarios/" + RunningStub.FAULT_SCENARIO + "/state"))
                .header("Content-Type", "application/json")
                .PUT(state == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{\"state\":\"" + state + "\"}"))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static int status(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** A stub that is not a rest client's stand-in. */
    private record AddressedStub(String name, StubAddress address) implements PartnerStub {

        @Override
        public void register(WireMockServer server) {
            server.stubFor(get(urlPathMatching("/ping")).willReturn(aResponse().withStatus(200)));
        }
    }

    /** A stub that answers one path, for the tests that need more than one partner. */
    private record NamedStub(String name) implements PartnerStub {

        @Override
        public void register(WireMockServer server) {
            server.stubFor(get(urlPathMatching("/ping")).willReturn(aResponse().withStatus(200)));
        }
    }
}
