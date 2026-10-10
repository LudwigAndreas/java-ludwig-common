package ru.ludwigandreas.testsupport.local.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApi;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApiStub;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;
import ru.ludwigandreas.testsupport.local.stub.StubServers;
import ru.ludwigandreas.testsupport.local.stub.WithPartnerStubs;

/**
 * Proves a stub is wired to its client by name, and that the wiring beats the service's own file.
 *
 * <p>The first test is the one the module stands on. The sample's {@code application.yml} names an
 * unresolvable remote host for the partner; if the stub's address did not take precedence, a laptop
 * would be calling whatever a real service's file names.
 */
@SampleContext
@WithPartnerStubs(SampleStubs.class)
class StubWiringIT {

    @Autowired
    private PartnerApi partner;

    @Autowired
    private StubServers stubs;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("the real client reaches the stub although application.yml names a remote host")
    void stubAddressBeatsTheServicesOwnConfiguration() {
        assertThat(partner.thing("7")).isEqualTo(PartnerApiStub.HEALTHY_BODY);

        RunningStub stub = stubs.stub("partner");
        assertThat(environment.getProperty("ludwig.rest-client.clients.partner.base-url"))
                .isEqualTo(stub.baseUrl());
        // The header comes from the client's own configuration, so its presence shows the request
        // went through the configured pipeline and not round it.
        stub.server().verify(getRequestedFor(urlPathEqualTo("/things/7"))
                .withHeader("X-Sample-Caller", equalTo("sample")));
    }

    @Test
    @DisplayName("a stub's own properties are applied alongside the base URL")
    void stubContributesFurtherProperties() {
        assertThat(environment.getProperty(PartnerApiStub.TOKEN_URL_PROPERTY))
                .isEqualTo(stubs.stub("partner").baseUrl() + "/token");
    }

    @Test
    @DisplayName("a configured port is the port the stub listens on")
    void configuredPortIsHonoured() throws IOException {
        int port = freePort();
        try (StubServers started = StubServers.start(new SampleStubs(),
                new MockEnvironment().withProperty("ludwig.local.stubs.partner.port", String.valueOf(port)))) {
            assertThat(started.stub("partner").port()).isEqualTo(port);
        }
    }

    @Test
    @DisplayName("two suites starting the same stub get different ports")
    void unconfiguredPortsDoNotCollide() {
        try (StubServers first = StubServers.start(new SampleStubs(), new MockEnvironment())) {
            try (StubServers second = StubServers.start(new SampleStubs(), new MockEnvironment())) {
                assertThat(first.stub("partner").port()).isNotEqualTo(second.stub("partner").port());
            }
        }
    }

    @Test
    @DisplayName("a configured port another process holds fails start-up naming the client and the port")
    void takenPortFailsNamingClientAndPort() throws IOException {
        try (ServerSocket holder = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int taken = holder.getLocalPort();
            MockEnvironment configured = new MockEnvironment()
                    .withProperty("ludwig.local.stubs.partner.port", String.valueOf(taken));

            assertThatThrownBy(() -> StubServers.start(new SampleStubs(), configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'partner'")
                    .hasMessageContaining(String.valueOf(taken));
        }
    }

    @Test
    @DisplayName("two stubs for one client fail start-up naming the client and both types")
    void duplicateClientNameIsRefused() {
        StubCatalog twice = () -> List.of(new PartnerApiStub(), new SecondPartnerStub());

        assertThatThrownBy(() -> StubServers.start(twice, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'partner'")
                .hasMessageContaining(PartnerApiStub.class.getName())
                .hasMessageContaining(SecondPartnerStub.class.getName());
    }

    @Test
    @DisplayName("a stub is not reachable on the machine's non-loopback address")
    void stubListensOnLoopbackOnly() throws IOException {
        Optional<InetAddress> external = nonLoopbackAddress();
        assumeTrue(external.isPresent(), "this machine has no usable non-loopback IPv4 address to try");

        assertThat(accepts(external.get(), stubs.stub("partner").port()))
                .as("the stub must not answer on %s", external.get())
                .isFalse();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * An address of this machine, other than loopback, on which a closed port really is closed.
     *
     * <p>The control probe is not paranoia. A VPN or proxy tunnel interface commonly accepts a
     * connection to any port and decides what to do with it afterwards, so "the connection was
     * accepted" says nothing about the stub on such an interface. An address only qualifies when a
     * port nothing listens on is refused there.
     */
    private static Optional<InetAddress> nonLoopbackAddress() throws IOException {
        int closedPort = freePort();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback() || network.isPointToPoint() || network.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && !accepts(address, closedPort)) {
                    return Optional.of(address);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean accepts(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 2000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    /** A second stand-in for the same client, which a catalog must not contain. */
    static class SecondPartnerStub implements PartnerStub {

        @Override
        public String name() {
            return "partner";
        }

        @Override
        public void register(WireMockServer server) {
            // Never started: the duplicate is refused before any server is.
        }
    }
}
